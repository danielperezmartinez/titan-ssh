//go:build unix

package dbus

import (
	"bytes"
	"context"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"golang.org/x/sys/unix"
)

// Conn is a connection to a message bus.
type Conn struct {
	c    *net.UnixConn
	name string // our unique name, from Hello

	writeMu sync.Mutex
	mu      sync.Mutex
	serial  uint32
	pending map[uint32]chan *Message
	watches map[int]*watch
	nextW   int
	closed  bool
	err     error
	done    chan struct{}
}

// watch receives the signals that match it.
type watch struct {
	match func(*Message) bool
	ch    chan *Message
}

// ErrClosed reports a call on, or a wait cut by, a closed connection.
var ErrClosed = errors.New("dbus: connection closed")

// Dial connects to the bus at address (DBUS_SESSION_BUS_ADDRESS syntax: the
// first unix:path= or unix:abstract= entry that answers), authenticates as
// this process's user and says Hello.
func Dial(address string) (*Conn, error) {
	var lastErr error = fmt.Errorf("dbus: no usable address in %q", address)
	for _, entry := range strings.Split(address, ";") {
		path, ok := unixPath(entry)
		if !ok {
			continue
		}
		raw, err := net.DialUnix("unix", nil, &net.UnixAddr{Name: path, Net: "unix"})
		if err != nil {
			lastErr = err
			continue
		}
		c, err := newConn(raw)
		if err != nil {
			raw.Close()
			lastErr = err
			continue
		}
		return c, nil
	}
	return nil, lastErr
}

// unixPath turns "unix:path=/run/user/1000/bus,guid=…" into the socket name
// (abstract ones with a leading "@").
func unixPath(entry string) (string, bool) {
	rest, ok := strings.CutPrefix(strings.TrimSpace(entry), "unix:")
	if !ok {
		return "", false
	}
	for _, kv := range strings.Split(rest, ",") {
		k, v, _ := strings.Cut(kv, "=")
		v = unescape(v)
		switch k {
		case "path":
			return v, true
		case "abstract":
			return "@" + v, true
		}
	}
	return "", false
}

// unescape undoes the %XX escapes of an address value.
func unescape(s string) string {
	if !strings.Contains(s, "%") {
		return s
	}
	var b strings.Builder
	for i := 0; i < len(s); i++ {
		if s[i] == '%' && i+2 < len(s) {
			if n, err := strconv.ParseUint(s[i+1:i+3], 16, 8); err == nil {
				b.WriteByte(byte(n))
				i += 2
				continue
			}
		}
		b.WriteByte(s[i])
	}
	return b.String()
}

func newConn(raw *net.UnixConn) (*Conn, error) {
	c := &Conn{c: raw, pending: map[uint32]chan *Message{}, watches: map[int]*watch{}, done: make(chan struct{})}
	_ = raw.SetDeadline(time.Now().Add(10 * time.Second))
	rest, err := c.auth()
	if err != nil {
		return nil, err
	}
	_ = raw.SetDeadline(time.Time{})
	go c.readLoop(rest)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	reply, err := c.Call(ctx, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "Hello", "")
	if err != nil {
		c.Close()
		return nil, err
	}
	if len(reply.Body) == 1 {
		c.name, _ = reply.Body[0].(string)
	}
	return c, nil
}

// auth runs the SASL exchange: EXTERNAL with our uid, and descriptor
// passing. It returns any bytes read past BEGIN.
func (c *Conn) auth() ([]byte, error) {
	uid := hex.EncodeToString([]byte(strconv.Itoa(os.Getuid())))
	var buf []byte
	readLine := func() (string, error) {
		tmp := make([]byte, 256)
		for {
			if i := bytes.Index(buf, []byte("\r\n")); i >= 0 {
				line := string(buf[:i])
				buf = buf[i+2:]
				return line, nil
			}
			if len(buf) > 4096 {
				return "", errors.New("dbus: authentication line too long")
			}
			n, err := c.c.Read(tmp)
			if err != nil {
				return "", fmt.Errorf("dbus: authentication: %w", err)
			}
			buf = append(buf, tmp[:n]...)
		}
	}
	if _, err := c.c.Write([]byte("\x00AUTH EXTERNAL " + uid + "\r\n")); err != nil {
		return nil, err
	}
	line, err := readLine()
	if err != nil {
		return nil, err
	}
	if !strings.HasPrefix(line, "OK ") {
		return nil, fmt.Errorf("dbus: the bus refused EXTERNAL authentication: %q", line)
	}
	if _, err := c.c.Write([]byte("NEGOTIATE_UNIX_FD\r\n")); err != nil {
		return nil, err
	}
	if line, err = readLine(); err != nil {
		return nil, err
	}
	if !strings.HasPrefix(line, "AGREE_UNIX_FD") {
		return nil, fmt.Errorf("dbus: the bus does not pass file descriptors: %q", line)
	}
	if _, err := c.c.Write([]byte("BEGIN\r\n")); err != nil {
		return nil, err
	}
	return buf, nil
}

// UniqueName is the connection's name on the bus (":1.42").
func (c *Conn) UniqueName() string { return c.name }

// Close closes the connection; pending calls fail with ErrClosed.
func (c *Conn) Close() error {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return nil
	}
	c.closed = true
	c.mu.Unlock()
	return c.c.Close()
}

// Done is closed when the connection ends.
func (c *Conn) Done() <-chan struct{} { return c.done }

// send writes m with a fresh serial and returns the serial.
func (c *Conn) send(m *Message) (uint32, chan *Message, error) {
	c.mu.Lock()
	if c.closed || c.err != nil {
		c.mu.Unlock()
		return 0, nil, ErrClosed
	}
	c.serial++
	if c.serial == 0 {
		c.serial = 1
	}
	m.Serial = c.serial
	var ch chan *Message
	if m.Type == TypeMethodCall && m.Flags&flagNoReplyExpected == 0 {
		ch = make(chan *Message, 1)
		c.pending[m.Serial] = ch
	}
	c.mu.Unlock()

	data, fds, err := m.marshal()
	if err != nil {
		c.forget(m.Serial)
		return 0, nil, err
	}
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	if len(fds) > 0 {
		_, _, err = c.c.WriteMsgUnix(data, unix.UnixRights(fds...), nil)
	} else {
		_, err = c.c.Write(data)
	}
	if err != nil {
		c.forget(m.Serial)
		return 0, nil, err
	}
	return m.Serial, ch, nil
}

func (c *Conn) forget(serial uint32) {
	c.mu.Lock()
	delete(c.pending, serial)
	c.mu.Unlock()
}

// Call calls a method and waits for its reply. An error reply comes back as
// an *Error. sig is the signature of args ("" for none).
func (c *Conn) Call(ctx context.Context, dest string, path ObjectPath, iface, member, sig string, args ...any) (*Message, error) {
	m := &Message{Type: TypeMethodCall, Flags: flagAllowInteractAuth, Destination: dest, Path: path, Interface: iface, Member: member, Signature: sig, Body: args}
	serial, ch, err := c.send(m)
	if err != nil {
		return nil, err
	}
	select {
	case reply := <-ch:
		if reply == nil {
			return nil, c.closeErr()
		}
		if reply.Type == TypeError {
			e := &Error{Name: reply.ErrorName}
			if len(reply.Body) > 0 {
				e.Message, _ = reply.Body[0].(string)
			}
			closeFiles(reply.Files)
			return nil, e
		}
		return reply, nil
	case <-ctx.Done():
		c.forget(serial)
		return nil, ctx.Err()
	}
}

// Send sends a method call without waiting for (or asking for) a reply.
func (c *Conn) Send(dest string, path ObjectPath, iface, member, sig string, args ...any) error {
	m := &Message{Type: TypeMethodCall, Flags: flagNoReplyExpected, Destination: dest, Path: path, Interface: iface, Member: member, Signature: sig, Body: args}
	_, _, err := c.send(m)
	return err
}

func (c *Conn) closeErr() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.err != nil && !errors.Is(c.err, net.ErrClosed) {
		return fmt.Errorf("%w: %v", ErrClosed, c.err)
	}
	return ErrClosed
}

// Watch delivers the signals that match (sender, path, interface, member;
// empty ones match anything) on the returned channel, until stop. It also
// asks the bus to route them here (AddMatch). The channel is closed when the
// connection ends or on stop.
func (c *Conn) Watch(ctx context.Context, sender string, path ObjectPath, iface, member string) (<-chan *Message, func(), error) {
	var rule []string
	rule = append(rule, "type='signal'")
	if sender != "" {
		rule = append(rule, "sender='"+sender+"'")
	}
	if path != "" {
		rule = append(rule, "path='"+string(path)+"'")
	}
	if iface != "" {
		rule = append(rule, "interface='"+iface+"'")
	}
	if member != "" {
		rule = append(rule, "member='"+member+"'")
	}
	match := strings.Join(rule, ",")
	w := &watch{
		match: func(m *Message) bool {
			return (path == "" || m.Path == path) && (iface == "" || m.Interface == iface) && (member == "" || m.Member == member)
		},
		ch: make(chan *Message, 16),
	}
	c.mu.Lock()
	if c.closed || c.err != nil {
		c.mu.Unlock()
		return nil, nil, ErrClosed
	}
	id := c.nextW
	c.nextW++
	c.watches[id] = w
	c.mu.Unlock()
	if _, err := c.Call(ctx, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "AddMatch", "s", match); err != nil {
		c.unwatch(id)
		return nil, nil, err
	}
	var once sync.Once
	stop := func() {
		once.Do(func() {
			if c.unwatch(id) {
				_ = c.Send("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "RemoveMatch", "s", match)
			}
		})
	}
	return w.ch, stop, nil
}

// unwatch drops a watch and closes its channel; false if it was gone.
func (c *Conn) unwatch(id int) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	w, ok := c.watches[id]
	if !ok {
		return false
	}
	delete(c.watches, id)
	close(w.ch)
	return true
}

// readLoop reads messages until the connection fails, handing replies to
// their callers and signals to the watches.
func (c *Conn) readLoop(buf []byte) {
	defer close(c.done)
	var fds []int
	data := make([]byte, 64*1024)
	oob := make([]byte, unix.CmsgSpace(64*4))
	var err error
	for {
		for {
			n, ferr := frameLength(buf)
			if ferr != nil {
				err = ferr
				break
			}
			if n == 0 || len(buf) < n {
				break
			}
			m, used, uerr := unmarshal(buf[:n], fds)
			if uerr != nil {
				err = uerr
				break
			}
			fds = fds[used:]
			buf = append(buf[:0:0], buf[n:]...)
			c.dispatch(m)
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
			break
		}
		if n == 0 {
			err = errors.New("dbus: the bus closed the connection")
			break
		}
	}
	closeFiles(fds)
	c.mu.Lock()
	c.err = err
	c.closed = true
	pending := c.pending
	c.pending = map[uint32]chan *Message{}
	watches := c.watches
	c.watches = map[int]*watch{}
	c.mu.Unlock()
	for _, ch := range pending {
		close(ch)
	}
	for _, w := range watches {
		close(w.ch)
	}
	c.c.Close()
}

func (c *Conn) dispatch(m *Message) {
	switch m.Type {
	case TypeMethodReturn, TypeError:
		c.mu.Lock()
		ch, ok := c.pending[m.ReplySerial]
		delete(c.pending, m.ReplySerial)
		c.mu.Unlock()
		if ok {
			ch <- m
			return
		}
	case TypeSignal:
		c.mu.Lock()
		var targets []*watch
		for _, w := range c.watches {
			if w.match(m) {
				targets = append(targets, w)
			}
		}
		// Delivered under the lock so that unwatch cannot close a channel
		// mid-send; a watcher that stops reading loses signals, never blocks
		// the connection.
		for _, w := range targets {
			select {
			case w.ch <- m:
			default:
			}
		}
		c.mu.Unlock()
		if len(targets) > 0 {
			return
		}
	case TypeMethodCall:
		// The agent exports nothing: answer so the caller does not wait.
		if m.Flags&flagNoReplyExpected == 0 {
			_, _, _ = c.send(&Message{Type: TypeError, ErrorName: "org.freedesktop.DBus.Error.UnknownMethod", ReplySerial: m.Serial, Destination: m.Sender, Signature: "s", Body: []any{"no such object"}})
		}
	}
	closeFiles(m.Files)
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

func closeFiles(fds []int) {
	for _, fd := range fds {
		_ = unix.Close(fd)
	}
}
