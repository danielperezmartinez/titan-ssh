package main

import (
	"net"
	"os"
	"path/filepath"
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
// opening HELLO. A var so tests can shorten it.
var helloTimeout = 10 * time.Second

// runDaemon listens on the Unix socket and serves the framed protocol, holding
// every session's PTY and ring buffer for their lifetime.
func runDaemon(sockPath string, bufCap int) error {
	if err := ensureSocketDir(filepath.Dir(sockPath)); err != nil {
		return err
	}
	_ = os.Remove(sockPath) // clear a stale socket from a previous run
	ln, err := listenPrivate(sockPath)
	if err != nil {
		return err
	}
	defer ln.Close()

	reg := session.NewRegistry(session.NewPty, bufCap)
	go func() {
		t := time.NewTicker(gcInterval)
		defer t.Stop()
		for range t.C {
			reg.GC(sessionTTL)
		}
	}()

	for {
		conn, err := ln.Accept()
		if err != nil {
			return err
		}
		go serveConn(conn, reg)
	}
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
	s, _, err := reg.AttachOrCreate(first.SessionID, clampSize(first.Cols, 80), clampSize(first.Rows, 24))
	if err != nil {
		_ = out(protocol.Frame{Type: protocol.TypeBye})
		return
	}
	_ = s.Handle(first, out, in)
}

// clampSize substitutes a sane default for a zero terminal dimension.
func clampSize(v, def uint16) uint16 {
	if v == 0 {
		return def
	}
	return v
}
