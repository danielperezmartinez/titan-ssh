package session

import (
	"testing"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/protocol"
)

// fakeFactory hands out fakePtys and remembers them.
type fakeFactory struct{ ptys []*fakePty }

func (f *fakeFactory) new(cols, rows uint16) (Pty, error) {
	p := newFakePty()
	f.ptys = append(f.ptys, p)
	return p, nil
}

func isClosed(p *fakePty) bool {
	select {
	case <-p.closed:
		return true
	default:
		return false
	}
}

// ADR-0014: a live session with no client is never reaped, however long it
// has been idle; only one whose shell exited is.
func TestGCKeepsDetachedLiveSessions(t *testing.T) {
	f := &fakeFactory{}
	reg := NewRegistry(f.new, 1024)
	t.Cleanup(func() { reg.CloseAll() })
	s, _, _ := reg.AttachOrCreate("idle", 80, 24)
	s.mu.Lock()
	s.lastUsed = time.Now().Add(-365 * 24 * time.Hour)
	s.detached = s.lastUsed
	s.mu.Unlock()

	if n := reg.GC(); n != 0 || reg.Count() != 1 || isClosed(f.ptys[0]) {
		t.Fatalf("GC reaped a live session: reaped=%d count=%d", n, reg.Count())
	}

	f.ptys[0].Close() // the shell exits
	waitClosed(t, s)
	if n := reg.GC(); n != 1 || reg.Count() != 0 {
		t.Fatalf("GC should reap the exited session: reaped=%d count=%d", n, reg.Count())
	}
}

func waitClosed(t *testing.T, s *Session) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for {
		s.mu.Lock()
		closed := s.closed
		s.mu.Unlock()
		if closed {
			return
		}
		if time.Now().After(deadline) {
			t.Fatal("the session never saw its PTY close")
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func TestCloseEndsOneSessionAndFreesItsID(t *testing.T) {
	f := &fakeFactory{}
	reg := NewRegistry(f.new, 1024)
	t.Cleanup(func() { reg.CloseAll() })
	reg.AttachOrCreate("a", 80, 24)
	reg.AttachOrCreate("b", 80, 24)

	if !reg.Close("a") {
		t.Fatal("Close should report the session existed")
	}
	if reg.Close("a") {
		t.Fatal("a second Close has nothing to close")
	}
	if !isClosed(f.ptys[0]) || isClosed(f.ptys[1]) {
		t.Fatal("Close must close only that session's PTY")
	}
	if _, created, _ := reg.AttachOrCreate("a", 80, 24); !created {
		t.Fatal("after Close the id must start a fresh session")
	}
}

func TestCloseAllEndsEverySession(t *testing.T) {
	f := &fakeFactory{}
	reg := NewRegistry(f.new, 1024)
	reg.AttachOrCreate("a", 80, 24)
	reg.AttachOrCreate("b", 80, 24)
	if n := reg.CloseAll(); n != 2 || reg.Count() != 0 {
		t.Fatalf("CloseAll: n=%d count=%d", n, reg.Count())
	}
	for i, p := range f.ptys {
		if !isClosed(p) {
			t.Fatalf("PTY %d left open", i)
		}
	}
}

// List reports attachment: Detached is set while no client is attached and
// cleared while one is.
func TestListReportsAttachmentAndHistory(t *testing.T) {
	f := &fakeFactory{}
	reg := NewRegistry(f.new, 1024)
	t.Cleanup(func() { reg.CloseAll() })
	s, _, _ := reg.AttachOrCreate("a", 80, 24)
	f.ptys[0].push("hello")
	waitHead(t, s, 5)

	in := reg.List()[0]
	if in.ID != "a" || in.Clients != 0 || in.Detached.IsZero() || in.BufferBytes != 5 || in.Closed {
		t.Fatalf("detached session: %+v", in)
	}

	clientIn := make(chan protocol.Frame)
	done := make(chan struct{})
	go func() {
		_ = s.Handle(protocol.Frame{Type: protocol.TypeHello, SessionID: "a"}, false,
			func(protocol.Frame) error { return nil }, clientIn)
		close(done)
	}()
	deadline := time.Now().Add(2 * time.Second)
	for reg.List()[0].Clients != 1 {
		if time.Now().After(deadline) {
			t.Fatal("the client never counted as attached")
		}
		time.Sleep(5 * time.Millisecond)
	}
	if in := reg.List()[0]; !in.Detached.IsZero() {
		t.Fatalf("an attached session has no detach time: %+v", in)
	}
	close(clientIn)
	<-done
	if in := reg.List()[0]; in.Clients != 0 || in.Detached.IsZero() {
		t.Fatalf("after the client left: %+v", in)
	}
}

// Info reports the size the session was created with, the last output time
// and window title, and Tail gives the end of the history for a preview.
func TestInfoAndTailDescribeTheSession(t *testing.T) {
	f := &fakeFactory{}
	reg := NewRegistry(f.new, 1024)
	t.Cleanup(func() { reg.CloseAll() })
	s, _, _ := reg.AttachOrCreate("s", 100, 30)
	if in := reg.List()[0]; !in.LastOutput.IsZero() || in.Cols != 100 || in.Rows != 30 {
		t.Fatalf("fresh info = %+v", in)
	}

	f.ptys[0].push("hello \x1b]0;my title\x07world")
	waitHead(t, s, 24)
	in := reg.List()[0]
	if in.LastOutput.IsZero() || in.Title != "my title" {
		t.Fatalf("info after output = %+v", in)
	}

	data, cols, rows, ok := reg.Tail("s", 5)
	if !ok || string(data) != "world" || cols != 100 || rows != 30 {
		t.Fatalf("Tail = %q %dx%d %v", data, cols, rows, ok)
	}
	if data, _, _, _ := reg.Tail("s", 1<<20); len(data) != 24 {
		t.Fatalf("Tail beyond the history = %d bytes; want all 24", len(data))
	}
	if _, _, _, ok := reg.Tail("missing", 5); ok {
		t.Fatal("Tail of a missing session must report it")
	}
}
