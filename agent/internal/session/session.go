// Package session owns the agent's persistent PTY sessions (ADR-0008).
//
// A Session wraps one PTY + shell on the destination and keeps a ring buffer of
// its output so a reconnecting client can replay from its last acknowledged
// offset. Sessions outlive any single client connection: the daemon
// (cmd/titan-agent) holds the Registry, and each client `exec` is a thin front
// that attaches by id. The PTY itself is created behind the Pty seam (see
// pty_*.go) so this logic is testable with a fake PTY.
package session

import (
	"errors"
	"slices"
	"sync"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/buffer"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/protocol"
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

	created time.Time

	mu       sync.Mutex
	cond     *sync.Cond // signaled when buf grows or the PTY closes
	closed   bool       // PTY reached EOF/error
	clients  int        // currently attached client connections
	lastUsed time.Time
	// detached is when the last client left; zero while one is attached.
	detached time.Time
	minAcked uint64
	// lastOutput is when the PTY last wrote anything; zero until it does.
	lastOutput time.Time
	cols, rows uint16 // the PTY's size, as last set
	titles     titleScanner
}

func newSession(id string, pty Pty, capBytes int, cols, rows uint16) *Session {
	now := time.Now()
	s := &Session{
		ID: id, pty: pty, buf: buffer.New(capBytes),
		created: now, lastUsed: now, detached: now, cols: cols, rows: rows,
	}
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
			s.lastOutput = time.Now()
			s.titles.Feed(b[:n])
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
// created is whether this attach created the session (Registry.AttachOrCreate);
// HELLO_OK echoes it so the client runs its start scripts only on a fresh PTY.
//
// out must be safe to call from two goroutines is NOT required — Handle
// serializes its own writes: the live streamer is the only writer once the loop
// starts, except REPLAY_FROM, which the caller-provided out should guard. The
// daemon's serveConn provides a mutex-guarded out.
func (s *Session) Handle(hello protocol.Frame, created bool, out func(protocol.Frame) error, in <-chan protocol.Frame) error {
	s.mu.Lock()
	s.clients++
	s.lastUsed = time.Now()
	s.detached = time.Time{}
	s.mu.Unlock()
	defer func() {
		s.mu.Lock()
		s.clients--
		s.lastUsed = time.Now()
		if s.clients == 0 {
			s.detached = s.lastUsed
		}
		s.mu.Unlock()
	}()

	if err := out(protocol.Frame{Type: protocol.TypeHelloOK, HeadOffset: s.buf.Head(), TailOffset: s.buf.Tail(), Created: created}); err != nil {
		return err
	}

	// Replay from the client's last offset (or from tail if it expired). On a
	// fresh session that offset belongs to one that is gone (e.g. the host
	// rebooted), so replay everything the new PTY has produced.
	last := hello.LastOffset
	if created {
		last = 0
	}
	from, history := s.buf.Since(last)
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
			if s.pty.Resize(f.Cols, f.Rows) == nil {
				s.mu.Lock()
				s.cols, s.rows = f.Cols, f.Rows
				s.mu.Unlock()
			}
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

// Registry indexes live sessions by id for attach-or-create, listing and closing.
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
	s := newSession(id, pty, r.bufCap, cols, rows)
	r.sessions[id] = s
	return s, true, nil
}

// Tail is the last n bytes of session id's retained output and the PTY size
// they were drawn for, so the client can show what the terminal looks like
// without attaching. The bool is false when the session does not exist.
func (r *Registry) Tail(id string, n int) (data []byte, cols, rows uint16, ok bool) {
	r.mu.Lock()
	s, ok := r.sessions[id]
	r.mu.Unlock()
	if !ok {
		return nil, 0, 0, false
	}
	head := s.buf.Head()
	from := uint64(0)
	if n >= 0 && head > uint64(n) {
		from = head - uint64(n)
	}
	_, data = s.buf.Since(from)
	s.mu.Lock()
	cols, rows = s.cols, s.rows
	s.mu.Unlock()
	return data, cols, rows, true
}

// GC drops the sessions whose shell has exited and that no client is still
// reading. Sessions never expire by time (ADR-0014): a live PTY stays until
// its shell exits or the user closes it (Close, CloseAll). Call it
// periodically from the daemon. Returns the number of sessions reaped.
func (r *Registry) GC() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	reaped := 0
	for id, s := range r.sessions {
		s.mu.Lock()
		done := s.clients == 0 && s.closed
		s.mu.Unlock()
		if done {
			_ = s.pty.Close()
			delete(r.sessions, id)
			reaped++
		}
	}
	return reaped
}

// Close ends session id at the user's request: it drops it from the registry,
// so the next HELLO for that id starts a fresh PTY, and closes its PTY, which
// ends the shell and everything attached to its terminal. It reports whether
// the session existed.
func (r *Registry) Close(id string) bool {
	r.mu.Lock()
	s, ok := r.sessions[id]
	delete(r.sessions, id)
	r.mu.Unlock()
	if ok {
		_ = s.pty.Close()
	}
	return ok
}

// CloseAll ends every session (the daemon's orderly stop) and returns how many
// there were.
func (r *Registry) CloseAll() int {
	r.mu.Lock()
	all := make([]*Session, 0, len(r.sessions))
	for id, s := range r.sessions {
		all = append(all, s)
		delete(r.sessions, id)
	}
	r.mu.Unlock()
	var wg sync.WaitGroup
	for _, s := range all {
		wg.Add(1)
		go func() { defer wg.Done(); _ = s.pty.Close() }()
	}
	wg.Wait()
	return len(all)
}

// Info is what the agent reports about one session to the user.
type Info struct {
	ID       string
	Created  time.Time
	LastUsed time.Time
	// Detached is when the last client left; zero while one is attached.
	Detached    time.Time
	Clients     int
	Closed      bool   // the shell exited; GC drops it once no client reads it
	BufferBytes uint64 // output history held for replay
	ShellPID    int    // 0 if the PTY does not expose it
	Shell       string // the shell's program, "" if the PTY does not tell
	// ForegroundPID is the process group leading the terminal on a POSIX
	// PTY; 0 where the terminal cannot tell (ConPTY) or it could not be read.
	ForegroundPID int
	// LastOutput is when the PTY last wrote anything; zero until it does.
	LastOutput time.Time
	Cols, Rows uint16
	Title      string // the last window title set with OSC 0/2, "" if none
}

// List describes every session, oldest first.
func (r *Registry) List() []Info {
	r.mu.Lock()
	all := make([]*Session, 0, len(r.sessions))
	for _, s := range r.sessions {
		all = append(all, s)
	}
	r.mu.Unlock()
	infos := make([]Info, 0, len(all))
	for _, s := range all {
		infos = append(infos, s.info())
	}
	slices.SortFunc(infos, func(a, b Info) int { return a.Created.Compare(b.Created) })
	return infos
}

func (s *Session) info() Info {
	s.mu.Lock()
	defer s.mu.Unlock()
	in := Info{
		ID:          s.ID,
		Created:     s.created,
		LastUsed:    s.lastUsed,
		Detached:    s.detached,
		Clients:     s.clients,
		Closed:      s.closed,
		BufferBytes: s.buf.Head() - s.buf.Tail(),
		LastOutput:  s.lastOutput,
		Cols:        s.cols,
		Rows:        s.rows,
		Title:       s.titles.title,
	}
	if p, ok := s.pty.(interface{ Pid() int }); ok {
		in.ShellPID = p.Pid()
	}
	if p, ok := s.pty.(interface{ Shell() string }); ok {
		in.Shell = p.Shell()
	}
	if p, ok := s.pty.(interface{ Foreground() int }); ok && !s.closed {
		in.ForegroundPID = p.Foreground()
	}
	return in
}

// Count returns the number of live sessions (for tests/diagnostics).
func (r *Registry) Count() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.sessions)
}
