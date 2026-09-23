// Package session owns the agent's persistent PTY sessions (ADR-0008).
//
// A Session wraps one PTY + shell on the destination and keeps a ring buffer of
// its output so a reconnecting client can replay from its last acknowledged
// offset. Sessions outlive any single client connection: the daemon
// (cmd/titan-agent) holds the Registry, and each client `exec` is a thin front
// that attaches by id. The PTY itself is created behind the Pty seam (see
// pty_unix.go / pty_other.go) so this logic is testable with a fake PTY.
package session

import (
	"errors"
	"sync"
	"time"

	"github.com/danigar/titan-ssh/agent/internal/buffer"
	"github.com/danigar/titan-ssh/agent/internal/protocol"
)

// Pty is the seam over a pseudo-terminal + child shell.
type Pty interface {
	Read(p []byte) (int, error)  // PTY master output (shell -> client)
	Write(p []byte) (int, error) // PTY master input (client -> shell)
	Resize(cols, rows uint16) error
	Close() error
}

// PtyFactory creates a PTY running the user's login shell at an initial size.
type PtyFactory func(cols, rows uint16) (Pty, error)

// Session is one persistent PTY that survives client disconnects.
type Session struct {
	ID  string
	pty Pty
	buf *buffer.Ring

	mu       sync.Mutex
	cond     *sync.Cond // signaled when buf grows or the PTY closes
	closed   bool       // PTY reached EOF/error
	clients  int        // currently attached client connections
	lastUsed time.Time
	minAcked uint64
}

func newSession(id string, pty Pty, capBytes int) *Session {
	s := &Session{ID: id, pty: pty, buf: buffer.New(capBytes), lastUsed: time.Now()}
	s.cond = sync.NewCond(&s.mu)
	go s.pump()
	return s
}

// pump copies PTY output into the ring buffer until the shell exits, waking any
// attached clients after each chunk.
func (s *Session) pump() {
	b := make([]byte, 32*1024)
	for {
		n, err := s.pty.Read(b)
		if n > 0 {
			s.buf.Append(b[:n])
			s.mu.Lock()
			s.cond.Broadcast()
			s.mu.Unlock()
		}
		if err != nil {
			s.mu.Lock()
			s.closed = true
			s.cond.Broadcast()
			s.mu.Unlock()
			return
		}
	}
}

// Handle drives one client connection: it replays the requested history, then
// streams live PTY output (as DATA frames) while applying client frames
// (INPUT/RESIZE/ACK/REPLAY_FROM/BYE). It returns when the client goes away; the
// Session keeps running for the next attach.
//
// out must be safe to call from two goroutines is NOT required — Handle
// serializes its own writes: the live streamer is the only writer once the loop
// starts, except REPLAY_FROM, which the caller-provided out should guard. The
// daemon's serveConn provides a mutex-guarded out.
func (s *Session) Handle(hello protocol.Frame, out func(protocol.Frame) error, in <-chan protocol.Frame) error {
	s.mu.Lock()
	s.clients++
	s.lastUsed = time.Now()
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		s.clients--
		s.lastUsed = time.Now()
		s.mu.Unlock()
	}()

	if err := out(protocol.Frame{Type: protocol.TypeHelloOK, HeadOffset: s.buf.Head(), TailOffset: s.buf.Tail()}); err != nil {
		return err
	}

	// Replay from the client's last offset (or from tail if it expired).
	from, history := s.buf.Since(hello.LastOffset)
	cursor := from
	if len(history) > 0 {
		if err := out(protocol.Frame{Type: protocol.TypeData, Offset: from, Bytes: history}); err != nil {
			return err
		}
		cursor = from + uint64(len(history))
	}

	// Live streamer: emit DATA as the buffer grows past cursor, until told to stop.
	stop := make(chan struct{})
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		for {
			s.mu.Lock()
			for !s.closed && s.buf.Head() == cursor && !closedChan(stop) {
				s.cond.Wait()
			}
			ptyClosed := s.closed
			s.mu.Unlock()
			if closedChan(stop) {
				return
			}
			fromL, data := s.buf.Since(cursor)
			if len(data) > 0 {
				if err := out(protocol.Frame{Type: protocol.TypeData, Offset: fromL, Bytes: data}); err != nil {
					return
				}
				cursor = fromL + uint64(len(data))
			}
			if ptyClosed && s.buf.Head() == cursor {
				return
			}
		}
	}()

	var loopErr error
	for f := range in {
		s.mu.Lock()
		s.lastUsed = time.Now()
		s.mu.Unlock()
		if f.Type == protocol.TypeBye {
			break
		}
		switch f.Type {
		case protocol.TypeInput:
			if _, err := s.pty.Write(f.Bytes); err != nil {
				loopErr = err
			}
		case protocol.TypeResize:
			_ = s.pty.Resize(f.Cols, f.Rows)
		case protocol.TypeReplayFrom:
			rf, data := s.buf.Since(f.OffsetArg)
			if len(data) > 0 {
				if err := out(protocol.Frame{Type: protocol.TypeData, Offset: rf, Bytes: data}); err != nil {
					loopErr = err
				}
			}
		case protocol.TypeAck:
			s.mu.Lock()
			if f.OffsetArg > s.minAcked {
				s.minAcked = f.OffsetArg
			}
			s.mu.Unlock()
		}
		if loopErr != nil {
			break
		}
	}

	close(stop)
	s.mu.Lock()
	s.cond.Broadcast()
	s.mu.Unlock()
	wg.Wait()
	return loopErr
}

func closedChan(ch chan struct{}) bool {
	select {
	case <-ch:
		return true
	default:
		return false
	}
}

// Registry indexes live sessions by id for attach-or-create + GC by idle TTL.
type Registry struct {
	mu       sync.Mutex
	sessions map[string]*Session
	newPty   PtyFactory
	bufCap   int
}

// NewRegistry returns a registry that builds sessions with newPty and retains
// bufCap bytes of output per session.
func NewRegistry(newPty PtyFactory, bufCap int) *Registry {
	return &Registry{sessions: map[string]*Session{}, newPty: newPty, bufCap: bufCap}
}

// AttachOrCreate returns the live session for id, creating (and starting) it on
// first use. The bool is true when a fresh session was created.
func (r *Registry) AttachOrCreate(id string, cols, rows uint16) (*Session, bool, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if s, ok := r.sessions[id]; ok {
		return s, false, nil
	}
	if r.newPty == nil {
		return nil, false, errors.New("session: no PTY factory configured")
	}
	pty, err := r.newPty(cols, rows)
	if err != nil {
		return nil, false, err
	}
	s := newSession(id, pty, r.bufCap)
	r.sessions[id] = s
	return s, true, nil
}

// GC closes and drops sessions that have no attached clients and have been idle
// longer than ttl (or whose PTY has closed). Call it periodically from the
// daemon. Returns the number of sessions reaped.
func (r *Registry) GC(ttl time.Duration) int {
	r.mu.Lock()
	defer r.mu.Unlock()
	reaped := 0
	now := time.Now()
	for id, s := range r.sessions {
		s.mu.Lock()
		idle := s.clients == 0 && (s.closed || now.Sub(s.lastUsed) > ttl)
		s.mu.Unlock()
		if idle {
			_ = s.pty.Close()
			delete(r.sessions, id)
			reaped++
		}
	}
	return reaped
}

// Count returns the number of live sessions (for tests/diagnostics).
func (r *Registry) Count() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.sessions)
}
