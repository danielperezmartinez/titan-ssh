//go:build linux

package pipewire

import (
	"context"
	"errors"
	"fmt"
	"net"
	"os"
	"sync"
	"syscall"

	"golang.org/x/sys/unix"
)

// Proxy ids the protocol fixes, and the opcodes used here (pipewire/core.h,
// pipewire/client.h).
const (
	idCore   = 0
	idClient = 1

	coreHello        = 1
	coreSync         = 2
	corePong         = 3
	coreCreateObject = 6

	clientUpdateProperties = 2

	evCoreInfo      = 0
	evCoreDone      = 1
	evCorePing      = 2
	evCoreError     = 3
	evCoreRemoveID  = 4
	evCoreBoundID   = 5
	evCoreAddMem    = 6
	evCoreRemoveMem = 7
	evCoreBoundProp = 8
)

// coreVersion is the core protocol version this client speaks (3: the
// 16-byte header with descriptors per message).
const coreVersion = 3

// maxMessage bounds a message from the server.
const maxMessage = 1 << 20

// Conn is a connection to a PipeWire server.
type Conn struct {
	c *net.UnixConn

	wmu sync.Mutex
	mu  sync.Mutex
	// seq numbers outgoing messages; syncSeq numbers the syncs, whose done
	// echoes it.
	seq     uint32
	syncSeq uint32
	nextID  uint32
	mems    map[uint32]*memBlock
	streams map[uint32]*Stream
	syncs   map[uint32]chan struct{}
	err     error
	done    chan struct{}
	// Version is the server's version string, from its info.
	Version string
}

// ErrClosed reports a closed connection.
var ErrClosed = errors.New("pipewire: connection closed")

// NewConn speaks the protocol on fd (from OpenPipeWireRemote, or a socket
// to the user's PipeWire), which it takes over. name is the application name
// the server shows.
func NewConn(ctx context.Context, fd int, name string) (*Conn, error) {
	f := os.NewFile(uintptr(fd), "pipewire")
	raw, err := net.FileConn(f)
	f.Close()
	if err != nil {
		return nil, fmt.Errorf("pipewire: %w", err)
	}
	uc, ok := raw.(*net.UnixConn)
	if !ok {
		raw.Close()
		return nil, errors.New("pipewire: the descriptor is not a unix socket")
	}
	c := &Conn{c: uc, nextID: 2, mems: map[uint32]*memBlock{}, streams: map[uint32]*Stream{}, syncs: map[uint32]chan struct{}{}, done: make(chan struct{})}
	go c.readLoop()
	if err := c.send(idCore, coreHello, pStruct(pInt(coreVersion)), nil); err != nil {
		c.Close()
		return nil, err
	}
	props := [][2]string{
		{"application.name", name},
		{"application.process.binary", "titan-agent"},
		{"application.process.id", fmt.Sprint(os.Getpid())},
	}
	if err := c.send(idClient, clientUpdateProperties, pStruct(pDict(props)), nil); err != nil {
		c.Close()
		return nil, err
	}
	if err := c.Sync(ctx); err != nil {
		c.Close()
		return nil, err
	}
	return c, nil
}

// Close closes the connection and every stream on it.
func (c *Conn) Close() error {
	err := c.c.Close()
	<-c.done
	return err
}

// Done is closed when the connection ends.
func (c *Conn) Done() <-chan struct{} { return c.done }

// Err is why the connection ended, once Done is closed.
func (c *Conn) Err() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.err
}

// send writes one message to object id.
func (c *Conn) send(id uint32, opcode uint8, body pod, fds []int) error {
	c.wmu.Lock()
	defer c.wmu.Unlock()
	c.mu.Lock()
	seq := c.seq
	c.seq++
	c.mu.Unlock()
	if len(body) > 0xffffff {
		return errors.New("pipewire: message too large")
	}
	msg := le.AppendUint32(nil, id)
	msg = le.AppendUint32(msg, uint32(opcode)<<24|uint32(len(body)))
	msg = le.AppendUint32(msg, seq)
	msg = le.AppendUint32(msg, uint32(len(fds)))
	msg = append(msg, body...)
	var err error
	if len(fds) > 0 {
		_, _, err = c.c.WriteMsgUnix(msg, unix.UnixRights(fds...), nil)
	} else {
		_, err = c.c.Write(msg)
	}
	return err
}

// Sync waits until the server has handled everything sent before it.
func (c *Conn) Sync(ctx context.Context) error {
	c.mu.Lock()
	if c.err != nil {
		c.mu.Unlock()
		return c.err
	}
	c.syncSeq++
	seq := c.syncSeq
	ch := make(chan struct{})
	c.syncs[seq] = ch
	c.mu.Unlock()
	if err := c.send(idCore, coreSync, pStruct(pInt(idCore), pInt(int32(seq))), nil); err != nil {
		return err
	}
	select {
	case <-ch:
		return nil
	case <-c.done:
		return c.Err()
	case <-ctx.Done():
		c.mu.Lock()
		delete(c.syncs, seq)
		c.mu.Unlock()
		return ctx.Err()
	}
}

// newID reserves a proxy id for a new object.
func (c *Conn) newID() uint32 {
	c.mu.Lock()
	defer c.mu.Unlock()
	id := c.nextID
	c.nextID++
	return id
}

// mem returns a memory block the server announced.
func (c *Conn) mem(id uint32) (*memBlock, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	m, ok := c.mems[id]
	if !ok {
		return nil, fmt.Errorf("pipewire: unknown memory %d", id)
	}
	return m, nil
}

// message is one message from the server with its descriptors.
type message struct {
	id     uint32
	opcode uint8
	body   []byte
	fds    []int
}

// fd takes the descriptor that the Fd value v indexes, leaving -1 in its
// place so it is not closed after the handler.
func (m *message) fd(v value) (int, error) {
	idx, err := v.long()
	if err != nil {
		return -1, err
	}
	if idx < 0 || idx >= int64(len(m.fds)) || m.fds[idx] < 0 {
		return -1, fmt.Errorf("pipewire: bad descriptor index %d", idx)
	}
	fd := m.fds[idx]
	m.fds[idx] = -1
	return fd, nil
}

func (c *Conn) readLoop() {
	var (
		buf  []byte
		fds  []int
		err  error
		data = make([]byte, 64*1024)
		oob  = make([]byte, unix.CmsgSpace(28*4))
	)
	for err == nil {
		for len(buf) >= 16 {
			size := int(le.Uint32(buf[4:]) & 0xffffff)
			nfds := int(le.Uint32(buf[12:]))
			if size > maxMessage || nfds > 28 {
				err = errors.New("pipewire: malformed message from the server")
				break
			}
			if len(buf) < 16+size {
				break
			}
			if nfds > len(fds) {
				err = fmt.Errorf("pipewire: message announces %d descriptors, %d arrived", nfds, len(fds))
				break
			}
			m := &message{
				id:     le.Uint32(buf),
				opcode: uint8(le.Uint32(buf[4:]) >> 24),
				body:   append([]byte(nil), buf[16:16+size]...),
				fds:    append([]int(nil), fds[:nfds]...),
			}
			fds = fds[nfds:]
			buf = append(buf[:0:0], buf[16+size:]...)
			if herr := c.dispatch(m); herr != nil {
				err = herr
			}
			for _, fd := range m.fds {
				if fd >= 0 {
					_ = unix.Close(fd)
				}
			}
			if err != nil {
				break
			}
		}
		if err != nil {
			break
		}
		n, oobn, _, _, rerr := c.c.ReadMsgUnix(data, oob)
		if oobn > 0 {
			fds = append(fds, parseRights(oob[:oobn])...)
		}
		if n > 0 {
			buf = append(buf, data[:n]...)
		}
		if rerr != nil {
			err = rerr
		} else if n == 0 {
			err = errors.New("pipewire: the server closed the connection")
		}
	}
	for _, fd := range fds {
		_ = unix.Close(fd)
	}
	if errors.Is(err, net.ErrClosed) {
		err = ErrClosed
	}
	c.mu.Lock()
	c.err = err
	streams := c.streams
	c.streams = map[uint32]*Stream{}
	mems := c.mems
	c.mems = map[uint32]*memBlock{}
	c.mu.Unlock()
	for _, s := range streams {
		s.end(err)
	}
	for _, m := range mems {
		m.close()
	}
	c.c.Close()
	close(c.done)
}

// dispatch handles one message; an error ends the connection.
func (c *Conn) dispatch(m *message) error {
	if m.id != idCore {
		c.mu.Lock()
		s := c.streams[m.id]
		c.mu.Unlock()
		if s != nil {
			s.event(m)
		}
		return nil
	}
	switch m.opcode {
	case evCoreInfo:
		if f, err := structArgs(m.body, 5); err == nil {
			c.Version, _ = f[4].string()
		}
	case evCoreDone:
		f, err := structArgs(m.body, 2)
		if err != nil {
			return err
		}
		seq, _ := f[1].uint()
		c.mu.Lock()
		ch, ok := c.syncs[seq]
		delete(c.syncs, seq)
		c.mu.Unlock()
		if ok {
			close(ch)
		}
	case evCorePing:
		f, err := structArgs(m.body, 2)
		if err != nil {
			return err
		}
		id, _ := f[0].int()
		seq, _ := f[1].int()
		return c.send(idCore, corePong, pStruct(pInt(id), pInt(seq)), nil)
	case evCoreError:
		f, err := structArgs(m.body, 4)
		if err != nil {
			return err
		}
		id, _ := f[0].uint()
		res, _ := f[2].int()
		text, _ := f[3].string()
		e := &ServerError{Res: res, Message: text}
		if id == idCore {
			return e
		}
		c.mu.Lock()
		s := c.streams[id]
		c.mu.Unlock()
		if s != nil {
			s.fail(e)
		}
	case evCoreRemoveID:
		f, err := structArgs(m.body, 1)
		if err != nil {
			return err
		}
		id, _ := f[0].uint()
		c.mu.Lock()
		s := c.streams[id]
		delete(c.streams, id)
		c.mu.Unlock()
		if s != nil {
			s.end(errors.New("pipewire: the server removed the stream"))
		}
	case evCoreAddMem:
		f, err := structArgs(m.body, 4)
		if err != nil {
			return err
		}
		id, _ := f[0].uint()
		typ, _ := f[1].uint()
		fd, err := m.fd(f[2])
		if err != nil {
			return err
		}
		flags, _ := f[3].uint()
		c.mu.Lock()
		old := c.mems[id]
		c.mems[id] = &memBlock{id: id, typ: typ, flags: flags, fd: fd}
		c.mu.Unlock()
		if old != nil {
			old.close()
		}
	case evCoreRemoveMem:
		f, err := structArgs(m.body, 1)
		if err != nil {
			return err
		}
		id, _ := f[0].uint()
		c.mu.Lock()
		old := c.mems[id]
		delete(c.mems, id)
		streams := make([]*Stream, 0, len(c.streams))
		for _, s := range c.streams {
			streams = append(streams, s)
		}
		c.mu.Unlock()
		if old != nil {
			// Wait for any stream still processing a cycle in it.
			for _, s := range streams {
				s.rtMu.Lock()
			}
			old.close()
			for _, s := range streams {
				s.rtMu.Unlock()
			}
		}
	}
	return nil
}

// ServerError is an error the server reported.
type ServerError struct {
	Res     int32
	Message string
}

func (e *ServerError) Error() string {
	return fmt.Sprintf("pipewire: %s (%s)", e.Message, syscall.Errno(-e.Res))
}

func parseRights(oob []byte) []int {
	msgs, err := unix.ParseSocketControlMessage(oob)
	if err != nil {
		return nil
	}
	var out []int
	for _, msg := range msgs {
		fds, err := unix.ParseUnixRights(&msg)
		if err == nil {
			for _, fd := range fds {
				syscall.CloseOnExec(fd)
			}
			out = append(out, fds...)
		}
	}
	return out
}
