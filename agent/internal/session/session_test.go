package session

import (
	"bytes"
	"io"
	"sync"
	"testing"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/protocol"
)

// fakePty is an in-memory PTY: Read delivers whatever is pushed via push(),
// Write is captured, Resize is recorded.
type fakePty struct {
	out    chan []byte
	closed chan struct{}
	mu     sync.Mutex
	writes bytes.Buffer
	sizes  [][2]uint16
}

func newFakePty() *fakePty {
	return &fakePty{out: make(chan []byte, 16), closed: make(chan struct{})}
}
func (p *fakePty) push(s string) { p.out <- []byte(s) }
func (p *fakePty) Read(b []byte) (int, error) {
	select {
	case d := <-p.out:
		return copy(b, d), nil
	case <-p.closed:
		return 0, io.EOF
	}
}
func (p *fakePty) Write(b []byte) (int, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.writes.Write(b)
}
func (p *fakePty) Resize(c, r uint16) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.sizes = append(p.sizes, [2]uint16{c, r})
	return nil
}
func (p *fakePty) Close() error {
	select {
	case <-p.closed:
	default:
		close(p.closed)
	}
	return nil
}

func waitHead(t *testing.T, s *Session, n uint64) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for s.buf.Head() < n {
		if time.Now().After(deadline) {
			t.Fatalf("buffer head never reached %d (was %d)", n, s.buf.Head())
		}
		time.Sleep(2 * time.Millisecond)
	}
}

// recv reads the next frame or fails on timeout.
func recv(t *testing.T, ch <-chan protocol.Frame) protocol.Frame {
	t.Helper()
	select {
	case f := <-ch:
		return f
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for a frame")
		return protocol.Frame{}
	}
}

func TestHandleReplaysThenStreamsLiveAndAppliesInput(t *testing.T) {
	fake := newFakePty()
	s := newSession("s1", fake, 1<<20)
	defer fake.Close()

	fake.push("hello ")
	waitHead(t, s, 6)

	frames := make(chan protocol.Frame, 32)
	var wmu sync.Mutex
	out := func(f protocol.Frame) error { wmu.Lock(); defer wmu.Unlock(); frames <- f; return nil }
	in := make(chan protocol.Frame, 8)
	go func() { _ = s.Handle(protocol.Frame{Type: protocol.TypeHello, SessionID: "s1", LastOffset: 0, Cols: 80, Rows: 24}, out, in) }()

	// 1) HELLO_OK announcing the range.
	if f := recv(t, frames); f.Type != protocol.TypeHelloOK || f.HeadOffset != 6 {
		t.Fatalf("expected HELLO_OK head=6, got %+v", f)
	}
	// 2) Replay of the buffered history from offset 0.
	if f := recv(t, frames); f.Type != protocol.TypeData || f.Offset != 0 || string(f.Bytes) != "hello " {
		t.Fatalf("expected replay DATA@0 'hello ', got %+v (%q)", f, f.Bytes)
	}
	// 3) Live output tees through with the correct offset.
	fake.push("world")
	if f := recv(t, frames); f.Type != protocol.TypeData || f.Offset != 6 || string(f.Bytes) != "world" {
		t.Fatalf("expected live DATA@6 'world', got %+v (%q)", f, f.Bytes)
	}

	// Client input reaches the PTY; resize is applied.
	in <- protocol.Frame{Type: protocol.TypeInput, Bytes: []byte("ls\n")}
	in <- protocol.Frame{Type: protocol.TypeResize, Cols: 100, Rows: 30}
	in <- protocol.Frame{Type: protocol.TypeBye}
	// Give the loop a moment to apply input before asserting.
	time.Sleep(50 * time.Millisecond)

	fake.mu.Lock()
	gotWrite := fake.writes.String()
	gotSizes := append([][2]uint16(nil), fake.sizes...)
	fake.mu.Unlock()
	if gotWrite != "ls\n" {
		t.Fatalf("PTY should have received 'ls\\n', got %q", gotWrite)
	}
	if len(gotSizes) != 1 || gotSizes[0] != [2]uint16{100, 30} {
		t.Fatalf("expected one resize 100x30, got %v", gotSizes)
	}
}

func TestHandleReplaysFromClientOffsetOnReconnect(t *testing.T) {
	fake := newFakePty()
	s := newSession("s2", fake, 1<<20)
	defer fake.Close()

	fake.push("abcdef")
	waitHead(t, s, 6)

	frames := make(chan protocol.Frame, 32)
	out := func(f protocol.Frame) error { frames <- f; return nil }
	in := make(chan protocol.Frame, 4)
	// Reconnect: client already applied through offset 3, wants replay from there.
	go func() { _ = s.Handle(protocol.Frame{Type: protocol.TypeHello, SessionID: "s2", LastOffset: 3, Cols: 80, Rows: 24}, out, in) }()

	if f := recv(t, frames); f.Type != protocol.TypeHelloOK {
		t.Fatalf("expected HELLO_OK, got %+v", f)
	}
	if f := recv(t, frames); f.Type != protocol.TypeData || f.Offset != 3 || string(f.Bytes) != "def" {
		t.Fatalf("expected replay DATA@3 'def', got %+v (%q)", f, f.Bytes)
	}
	in <- protocol.Frame{Type: protocol.TypeBye}
}

func TestRegistryAttachOrCreateReusesSession(t *testing.T) {
	made := 0
	reg := NewRegistry(func(c, r uint16) (Pty, error) { made++; return newFakePty(), nil }, 1<<16)
	s1, created1, err := reg.AttachOrCreate("x", 80, 24)
	if err != nil || !created1 {
		t.Fatalf("first attach should create: created=%v err=%v", created1, err)
	}
	s2, created2, _ := reg.AttachOrCreate("x", 80, 24)
	if created2 || s1 != s2 {
		t.Fatalf("second attach should reuse the same session")
	}
	if made != 1 || reg.Count() != 1 {
		t.Fatalf("expected exactly one PTY/session, made=%d count=%d", made, reg.Count())
	}
}
