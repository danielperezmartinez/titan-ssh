//go:build linux

// Package wayland is a minimal Wayland client (ADR-0031 §2): the wire
// protocol over the compositor's Unix socket, with descriptors passed as
// SCM_RIGHTS, the registry, shm buffers in memfd and the outputs. It knows
// only what the remote desktop needs on the wlroots family of compositors;
// the capture and input protocols live with their users (internal/screen,
// internal/inject). No cgo and no libwayland.
package wayland

import (
	"encoding/binary"
	"errors"
	"fmt"
	"math"
	"net"
	"os"
	"path/filepath"
	"sync"

	"golang.org/x/sys/unix"
)

// ne is the wire's byte order: the host's own.
var ne = binary.NativeEndian

// displayID is wl_display, object 1 on every connection.
const displayID = 1

// maxMessage is the largest message the 16-bit size field allows.
const maxMessage = 1 << 16

// Handler receives one object's events, on the connection's reading
// goroutine. It may send requests, but must not wait for other events.
type Handler func(opcode uint16, m *Message)

// Conn is a connection to a Wayland compositor.
type Conn struct {
	sock *net.UnixConn

	wmu sync.Mutex // serializes writes

	mu       sync.Mutex
	handlers map[uint32]Handler
	free     []uint32
	next     uint32
	err      error
	done     chan struct{}

	// Globals the registry announced, by name; registry is its object.
	registry uint32
	globals  map[uint32]Global
	onGlobal []func(Global, bool)
}

// Global is a global object the compositor announces.
type Global struct {
	Name      uint32
	Interface string
	Version   uint32
}

// ErrClosed reports a connection that ended.
var ErrClosed = errors.New("wayland: connection closed")

// ProtocolError is the compositor's wl_display.error: fatal for the
// connection.
type ProtocolError struct {
	Object  uint32
	Code    uint32
	Message string
}

func (e *ProtocolError) Error() string {
	return fmt.Sprintf("wayland: protocol error on object %d, code %d: %s", e.Object, e.Code, e.Message)
}

// SocketPath is where the compositor of display listens: display itself if
// it is absolute, else under runtimeDir; "wayland-0" when empty.
func SocketPath(runtimeDir, display string) string {
	if display == "" {
		display = "wayland-0"
	}
	if filepath.IsAbs(display) {
		return display
	}
	return filepath.Join(runtimeDir, display)
}

// Dial connects to the compositor at path, reads its globals and returns
// once it has them all.
func Dial(path string) (*Conn, error) {
	sock, err := net.DialUnix("unix", nil, &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		return nil, err
	}
	c := &Conn{
		sock:     sock,
		handlers: map[uint32]Handler{displayID: nil},
		next:     2,
		done:     make(chan struct{}),
		globals:  map[uint32]Global{},
	}
	c.handlers[displayID] = c.displayEvent
	go c.read()
	if c.registry, err = c.Create(c.registryEvent, displayID, 1, New{}); err != nil { // get_registry
		c.Close()
		return nil, err
	}
	if err := c.Roundtrip(); err != nil {
		c.Close()
		return nil, err
	}
	return c, nil
}

// Close ends the connection.
func (c *Conn) Close() error {
	c.fail(ErrClosed)
	return c.sock.Close()
}

// Done is closed when the connection ends; Err says why.
func (c *Conn) Done() <-chan struct{} { return c.done }

// Err is why the connection ended, or nil while it lives.
func (c *Conn) Err() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.err
}

func (c *Conn) fail(err error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.err == nil {
		c.err = err
		close(c.done)
	}
}

// newID allocates a client object that h serves (h may be nil for objects
// without events). Only Create calls it, under wmu: a fresh id must reach
// the compositor in order.
func (c *Conn) newID(h Handler) uint32 {
	c.mu.Lock()
	defer c.mu.Unlock()
	var id uint32
	if n := len(c.free); n > 0 {
		id, c.free = c.free[n-1], c.free[:n-1]
	} else {
		id = c.next
		c.next++
	}
	if h == nil {
		h = func(uint16, *Message) {}
	}
	c.handlers[id] = h
	return id
}

// Forget stops delivering id's events, as after its destructor request; the
// id itself is reused once the compositor confirms with delete_id.
func (c *Conn) Forget(id uint32) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if _, ok := c.handlers[id]; ok {
		c.handlers[id] = func(uint16, *Message) {}
	}
}

// Request argument types beyond uint32, int32, string and []byte (an array).
type (
	// Fd is a file descriptor passed with the request; the connection does
	// not close it.
	Fd int
	// Fixed is a 24.8 fixed-point number.
	Fixed float64
	// New stands for the id of the object a Create makes.
	New struct{}
	// Object is an object argument (0 is null).
	Object uint32
)

// Request sends a request to object: opcode and its arguments in order.
func (c *Conn) Request(object uint32, opcode uint16, args ...any) error {
	c.wmu.Lock()
	defer c.wmu.Unlock()
	return c.request(object, opcode, 0, args)
}

// Create sends a request that makes a new object, served by h: the New
// among args is its id, which it returns. The compositor wants new ids in
// the order they were allocated, so both happen under one lock.
func (c *Conn) Create(h Handler, object uint32, opcode uint16, args ...any) (uint32, error) {
	c.wmu.Lock()
	defer c.wmu.Unlock()
	id := c.newID(h)
	return id, c.request(object, opcode, id, args)
}

// request writes one request, with id for its New. Called under wmu.
func (c *Conn) request(object uint32, opcode uint16, id uint32, args []any) error {
	b := make([]byte, 8, 64)
	var fds []int
	for _, a := range args {
		switch v := a.(type) {
		case uint32:
			b = ne.AppendUint32(b, v)
		case int32:
			b = ne.AppendUint32(b, uint32(v))
		case int:
			b = ne.AppendUint32(b, uint32(int32(v)))
		case New:
			b = ne.AppendUint32(b, id)
		case Object:
			b = ne.AppendUint32(b, uint32(v))
		case Fixed:
			b = ne.AppendUint32(b, uint32(int32(math.Round(float64(v)*256))))
		case string:
			b = ne.AppendUint32(b, uint32(len(v)+1))
			b = append(b, v...)
			b = append(b, 0)
			b = pad(b)
		case []byte:
			b = ne.AppendUint32(b, uint32(len(v)))
			b = append(b, v...)
			b = pad(b)
		case Fd:
			fds = append(fds, int(v))
		default:
			return fmt.Errorf("wayland: request argument of type %T", a)
		}
	}
	if len(b) >= maxMessage {
		return fmt.Errorf("wayland: request of %d bytes", len(b))
	}
	ne.PutUint32(b[0:], object)
	ne.PutUint32(b[4:], uint32(len(b))<<16|uint32(opcode))
	if err := c.Err(); err != nil {
		return err
	}
	var oob []byte
	if len(fds) > 0 {
		oob = unix.UnixRights(fds...)
	}
	_, _, err := c.sock.WriteMsgUnix(b, oob, nil)
	return err
}

func pad(b []byte) []byte {
	for len(b)%4 != 0 {
		b = append(b, 0)
	}
	return b
}

// Roundtrip waits until the compositor has handled every request sent so
// far and the events they caused have been delivered.
func (c *Conn) Roundtrip() error {
	got := make(chan struct{})
	if _, err := c.Create(func(op uint16, _ *Message) {
		if op == 0 { // done
			close(got)
		}
	}, displayID, 0, New{}); err != nil { // sync
		return err
	}
	select {
	case <-got:
		return nil
	case <-c.done:
		return c.Err()
	}
}

// read delivers events until the connection ends.
func (c *Conn) read() {
	buf := make([]byte, 0, 2*maxMessage)
	chunk := make([]byte, maxMessage)
	oob := make([]byte, unix.CmsgSpace(28*4))
	var fds []int
	defer func() {
		for _, fd := range fds {
			unix.Close(fd)
		}
	}()
	for {
		n, oobn, _, _, err := c.sock.ReadMsgUnix(chunk, oob)
		if oobn > 0 && oobn <= len(oob) {
			if msgs, perr := unix.ParseSocketControlMessage(oob[:oobn]); perr == nil {
				for _, m := range msgs {
					if got, rerr := unix.ParseUnixRights(&m); rerr == nil {
						fds = append(fds, got...)
					}
				}
			}
		}
		n = max(n, 0) // -1 on some errors
		if n < 0 {    // on some errors
			n = 0
		}
		buf = append(buf, chunk[:n]...)
		for len(buf) >= 8 {
			size := int(ne.Uint32(buf[4:]) >> 16)
			if size < 8 {
				c.fail(fmt.Errorf("wayland: event of %d bytes", size))
				return
			}
			if len(buf) < size {
				break
			}
			obj := ne.Uint32(buf[0:])
			op := uint16(ne.Uint32(buf[4:]))
			m := &Message{data: buf[8:size], fds: &fds}
			c.mu.Lock()
			h := c.handlers[obj]
			c.mu.Unlock()
			if h != nil {
				h(op, m)
			}
			buf = append(buf[:0], buf[size:]...)
		}
		if err != nil {
			c.fail(fmt.Errorf("%w: %v", ErrClosed, err))
			return
		}
		if n == 0 {
			c.fail(ErrClosed)
			return
		}
	}
}

// displayEvent handles wl_display: error and delete_id.
func (c *Conn) displayEvent(op uint16, m *Message) {
	switch op {
	case 0: // error
		e := &ProtocolError{Object: m.Uint(), Code: m.Uint(), Message: m.String()}
		c.fail(e)
	case 1: // delete_id
		id := m.Uint()
		c.mu.Lock()
		if _, ok := c.handlers[id]; ok {
			delete(c.handlers, id)
			c.free = append(c.free, id)
		}
		c.mu.Unlock()
	}
}

// registryEvent records the globals as they come and go.
func (c *Conn) registryEvent(op uint16, m *Message) {
	var g Global
	added := op == 0
	switch op {
	case 0: // global
		g = Global{Name: m.Uint(), Interface: m.String(), Version: m.Uint()}
		c.mu.Lock()
		c.globals[g.Name] = g
	case 1: // global_remove
		name := m.Uint()
		c.mu.Lock()
		g = c.globals[name]
		delete(c.globals, name)
	default:
		return
	}
	watchers := append([]func(Global, bool){}, c.onGlobal...)
	c.mu.Unlock()
	for _, w := range watchers {
		w(g, added)
	}
}

// Globals lists the globals announced now.
func (c *Conn) Globals() []Global {
	c.mu.Lock()
	defer c.mu.Unlock()
	out := make([]Global, 0, len(c.globals))
	for _, g := range c.globals {
		out = append(out, g)
	}
	return out
}

// Find returns the first global with this interface.
func (c *Conn) Find(iface string) (Global, bool) {
	for _, g := range c.Globals() {
		if g.Interface == iface {
			return g, true
		}
	}
	return Global{}, false
}

// WatchGlobals calls f for every global added (true) or removed (false) from
// now on, on the reading goroutine.
func (c *Conn) WatchGlobals(f func(Global, bool)) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.onGlobal = append(c.onGlobal, f)
}

// Bind binds global g at version (at most what it offers) to a new object
// served by h.
func (c *Conn) Bind(g Global, version uint32, h Handler) (uint32, uint32, error) {
	version = min(version, g.Version)
	id, err := c.Create(h, c.registry, 0, g.Name, g.Interface, version, New{}) // bind
	return id, version, err
}

// Message is one event's arguments, read in order.
type Message struct {
	data []byte
	fds  *[]int
	bad  bool
}

func (m *Message) take(n int) []byte {
	if len(m.data) < n {
		m.bad = true
		m.data = nil
		return make([]byte, n)
	}
	b := m.data[:n]
	m.data = m.data[n:]
	return b
}

// Uint reads a uint (also an object or a new_id).
func (m *Message) Uint() uint32 { return ne.Uint32(m.take(4)) }

// Int reads an int.
func (m *Message) Int() int32 { return int32(m.Uint()) }

// Fixed reads a 24.8 fixed-point number.
func (m *Message) Fixed() float64 { return float64(m.Int()) / 256 }

// String reads a string (without its terminating NUL).
func (m *Message) String() string {
	n := int(m.Uint())
	b := m.take((n + 3) &^ 3)
	if n == 0 {
		return ""
	}
	return string(b[:n-1])
}

// Array reads an array.
func (m *Message) Array() []byte {
	n := int(m.Uint())
	b := m.take((n + 3) &^ 3)
	return append([]byte(nil), b[:n]...)
}

// Fd takes the next descriptor that came with the events; the caller owns
// it. -1 if there is none.
func (m *Message) Fd() int {
	if len(*m.fds) == 0 {
		return -1
	}
	fd := (*m.fds)[0]
	*m.fds = (*m.fds)[1:]
	return fd
}

// Short reports a message that ended before its arguments did.
func (m *Message) Short() bool { return m.bad }

// Memfd is anonymous shared memory to pass to the compositor.
type Memfd struct {
	Fd   int
	Data []byte
}

// NewMemfd creates size bytes of shared memory, mapped read-write.
func NewMemfd(name string, size int) (*Memfd, error) {
	fd, err := unix.MemfdCreate(name, unix.MFD_CLOEXEC)
	if err != nil {
		return nil, os.NewSyscallError("memfd_create", err)
	}
	if err := unix.Ftruncate(fd, int64(size)); err != nil {
		unix.Close(fd)
		return nil, os.NewSyscallError("ftruncate", err)
	}
	data, err := unix.Mmap(fd, 0, size, unix.PROT_READ|unix.PROT_WRITE, unix.MAP_SHARED)
	if err != nil {
		unix.Close(fd)
		return nil, os.NewSyscallError("mmap", err)
	}
	return &Memfd{Fd: fd, Data: data}, nil
}

// Close unmaps the memory and closes the descriptor.
func (f *Memfd) Close() {
	if f.Data != nil {
		_ = unix.Munmap(f.Data)
		f.Data = nil
	}
	if f.Fd >= 0 {
		unix.Close(f.Fd)
		f.Fd = -1
	}
}
