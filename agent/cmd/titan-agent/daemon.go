package main

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"net"
	"os"
	"sync"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/protocol"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/session"
)

const (
	gcInterval = 60 * time.Second
	sessionTTL = 30 * time.Minute
)

// helloTimeout bounds how long a fresh connection may stay silent before its
// preamble, and then before its opening HELLO. A var so tests can shorten it.
var helloTimeout = 10 * time.Second

// runDaemon is the --daemon mode: it becomes the user's only daemon, publishes
// where it listens and serves the framed protocol, holding every session's PTY
// and ring buffer for their lifetime. It returns nil at once, touching
// nothing, when another daemon already holds the lock.
func runDaemon(stateDir string, bufCap int) error {
	if err := ensureStateDir(stateDir); err != nil {
		return withCode(codeStateDir, err)
	}
	d, err := startDaemon(stateDir, bufCap, session.NewPty)
	if errors.Is(err, errLocked) {
		return nil // a live daemon serves this user; the front connects to it
	}
	if err != nil {
		return err
	}
	defer d.close()

	go func() {
		t := time.NewTicker(gcInterval)
		defer t.Stop()
		for range t.C {
			d.reg.GC(sessionTTL)
		}
	}()
	return d.serve()
}

// daemon is a started daemon: it holds the lock, listens on loopback and has
// published its state file.
type daemon struct {
	dir   string
	lock  *os.File
	ln    net.Listener
	token []byte
	reg   *session.Registry
	conns sync.WaitGroup // connection goroutines started by serve
}

// startDaemon takes the single-instance lock, listens on a random loopback
// port and only then publishes {port, token, pid}, so a front never finds a
// state file for a port nobody listens on yet. It returns errLocked if another
// daemon holds the lock.
func startDaemon(dir string, bufCap int, newPty session.PtyFactory) (*daemon, error) {
	lock, err := lockDaemon(dir)
	if errors.Is(err, errLocked) {
		return nil, err
	}
	if err != nil {
		return nil, withCode(codeLock, err)
	}
	// Only 127.0.0.1, never all interfaces: that would expose the port to the
	// network (and trip the Windows firewall prompt).
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		lock.Close()
		return nil, err
	}
	token := make([]byte, tokenLen)
	if _, err := rand.Read(token); err != nil {
		ln.Close()
		lock.Close()
		return nil, err
	}
	st := agentState{
		Schema: stateSchema,
		Agent:  version,
		Port:   ln.Addr().(*net.TCPAddr).Port,
		Token:  hex.EncodeToString(token),
		PID:    os.Getpid(),
	}
	if err := writeState(dir, st); err != nil {
		ln.Close()
		lock.Close()
		return nil, err
	}
	return &daemon{dir: dir, lock: lock, ln: ln, token: token, reg: session.NewRegistry(newPty, bufCap)}, nil
}

// serve accepts connections until the listener closes. Each one must pass the
// preamble before it reaches serveConn; a failed one is closed without a word.
func (d *daemon) serve() error {
	for {
		conn, err := d.ln.Accept()
		if errors.Is(err, net.ErrClosed) {
			return err
		}
		if err != nil {
			// Transient (e.g. out of file descriptors): returning would take
			// every live session down with the daemon.
			time.Sleep(100 * time.Millisecond)
			continue
		}
		d.conns.Add(1)
		go func() {
			defer d.conns.Done()
			if err := acceptPreamble(conn, d.token); err != nil {
				conn.Close()
				return
			}
			serveConn(conn, d.reg)
		}()
	}
}

// close stops listening, drops the state file if it is still this daemon's and
// releases the lock. Open connections and live sessions are left alone.
func (d *daemon) close() {
	d.ln.Close()
	removeOwnState(d.dir, os.Getpid())
	d.lock.Close()
}

// serveConn runs one client connection: decode frames, wait for the opening
// HELLO, attach-or-create its session and hand off to Session.Handle. Writes are
// serialized so the replay and the live tee never interleave a frame's bytes.
//
// It returns only once its reader goroutine has exited, whichever way the
// connection ends: client gone or silent before HELLO, attach failure, or
// Handle returning while the client is still sending.
func serveConn(conn net.Conn, reg *session.Registry) {
	done := make(chan struct{})
	var wg sync.WaitGroup
	defer wg.Wait()
	defer conn.Close() // unblocks a reader stuck in Read
	defer close(done)  // unblocks a reader stuck sending on in

	dec := &protocol.Decoder{}
	in := make(chan protocol.Frame, 64)

	var writeMu sync.Mutex
	out := func(f protocol.Frame) error {
		writeMu.Lock()
		defer writeMu.Unlock()
		_, err := conn.Write(protocol.Encode(f))
		return err
	}

	// A client that connects but never says HELLO must not pin the connection.
	_ = conn.SetReadDeadline(time.Now().Add(helloTimeout))

	helloCh := make(chan protocol.Frame, 1)
	wg.Add(1)
	go func() {
		defer wg.Done()
		defer close(in)
		gotHello := false
		defer func() {
			if !gotHello {
				close(helloCh) // tell serveConn no HELLO is coming
			}
		}()
		buf := make([]byte, 32*1024)
		for {
			n, err := conn.Read(buf)
			if n > 0 {
				frames, derr := dec.Feed(buf[:n])
				if derr != nil {
					return
				}
				for _, f := range frames {
					if !gotHello {
						if f.Type != protocol.TypeHello {
							return // protocol violation: HELLO must come first
						}
						gotHello = true
						helloCh <- f // buffered: never blocks
						continue
					}
					select {
					case in <- f:
					case <-done:
						return
					}
				}
			}
			if err != nil {
				return
			}
		}
	}()

	first, ok := <-helloCh
	if !ok {
		return // client closed, errored or stayed silent before HELLO
	}
	_ = conn.SetReadDeadline(time.Time{})
	s, created, err := reg.AttachOrCreate(first.SessionID, clampSize(first.Cols, 80), clampSize(first.Rows, 24))
	if err != nil {
		_ = out(protocol.Frame{Type: protocol.TypeBye})
		return
	}
	_ = s.Handle(first, created, out, in)
}

// clampSize substitutes a sane default for a zero terminal dimension.
func clampSize(v, def uint16) uint16 {
	if v == 0 {
		return def
	}
	return v
}
