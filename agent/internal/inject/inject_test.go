package inject

import (
	"fmt"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/protocol"
)

// fakeInjector records every call as a line.
type fakeInjector struct {
	mu      sync.Mutex
	calls   []string
	blocked bool
}

func (f *fakeInjector) log(format string, a ...any) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls = append(f.calls, fmt.Sprintf(format, a...))
	return nil
}
func (f *fakeInjector) Move(dx, dy int) error           { return f.log("move %d %d", dx, dy) }
func (f *fakeInjector) Button(b uint8, down bool) error { return f.log("button %d %v", b, down) }
func (f *fakeInjector) Scroll(dx, dy int) error         { return f.log("scroll %d %d", dx, dy) }
func (f *fakeInjector) Text(s string) error             { return f.log("text %q", s) }
func (f *fakeInjector) Key(k uint16, m, a uint8) error  { return f.log("key %d %d %d", k, m, a) }
func (f *fakeInjector) Blocked() bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.blocked
}

func (f *fakeInjector) snapshot() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string(nil), f.calls...)
}

// readFrame reads frames from c until one arrives or the timeout passes.
func readFrame(t *testing.T, c net.Conn, dec *protocol.Decoder) protocol.Frame {
	t.Helper()
	_ = c.SetReadDeadline(time.Now().Add(2 * time.Second))
	buf := make([]byte, 256)
	for {
		n, err := c.Read(buf)
		frames, derr := dec.Feed(buf[:n])
		if derr != nil {
			t.Fatal(derr)
		}
		if len(frames) > 0 {
			return frames[0]
		}
		if err != nil {
			t.Fatalf("no frame: %v", err)
		}
	}
}

func waitFor(t *testing.T, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatal("condition not met in time")
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func TestServeAppliesFramesAndReleasesWhatStaysPressed(t *testing.T) {
	inj := &fakeInjector{}
	client, server := net.Pipe()
	done := make(chan error, 1)
	go func() { done <- Serve(server, inj, &sync.Mutex{}) }()

	dec := &protocol.Decoder{}
	if f := readFrame(t, client, dec); f.Type != protocol.TypeInputReady || f.Blocked {
		t.Fatalf("first frame = %+v, want INPUT_READY not blocked", f)
	}
	wire := []protocol.Frame{
		{Type: protocol.TypePointerMove, DX: 5, DY: -2},
		{Type: protocol.TypePointerButton, Button: protocol.ButtonLeft, Pressed: true},
		{Type: protocol.TypeKey, Key: protocol.KeyShift, Action: protocol.ActionDown},
		{Type: protocol.TypeKey, Key: protocol.KeyA, Mods: protocol.ModCtrl, Action: protocol.ActionPress},
		{Type: protocol.TypeKey, Key: protocol.KeyAlt, Action: protocol.ActionDown},
		{Type: protocol.TypeKey, Key: protocol.KeyAlt, Action: protocol.ActionUp},
		{Type: protocol.TypeScroll, DY: 120},
		{Type: protocol.TypeText, Bytes: []byte("hola ñ")},
	}
	for _, f := range wire {
		if _, err := client.Write(protocol.Encode(f)); err != nil {
			t.Fatal(err)
		}
	}
	waitFor(t, func() bool { return len(inj.snapshot()) == len(wire) })
	client.Close() // the network drops mid-drag, with Shift held
	if err := <-done; err != nil {
		t.Fatalf("Serve = %v", err)
	}
	got := inj.snapshot()
	want := []string{
		"move 5 -2",
		"button 1 true",
		fmt.Sprintf("key %d 0 1", protocol.KeyShift),
		fmt.Sprintf("key %d 2 0", protocol.KeyA),
		fmt.Sprintf("key %d 0 1", protocol.KeyAlt),
		fmt.Sprintf("key %d 0 2", protocol.KeyAlt),
		"scroll 0 120",
		`text "hola ñ"`,
		// released on close: the button and Shift, not Alt
		"button 1 false",
		fmt.Sprintf("key %d 0 2", protocol.KeyShift),
	}
	if fmt.Sprint(got) != fmt.Sprint(want) {
		t.Fatalf("calls:\n got %q\nwant %q", got, want)
	}
}

func TestServeReportsBlockedChanges(t *testing.T) {
	old := BlockedPoll
	BlockedPoll = 10 * time.Millisecond
	defer func() { BlockedPoll = old }()

	inj := &fakeInjector{}
	client, server := net.Pipe()
	defer client.Close()
	go func() { _ = Serve(server, inj, &sync.Mutex{}) }()
	dec := &protocol.Decoder{}
	readFrame(t, client, dec) // INPUT_READY

	inj.mu.Lock()
	inj.blocked = true
	inj.mu.Unlock()
	if f := readFrame(t, client, dec); f.Type != protocol.TypeInputReady || !f.Blocked {
		t.Fatalf("after locking: %+v, want INPUT_READY blocked", f)
	}
	inj.mu.Lock()
	inj.blocked = false
	inj.mu.Unlock()
	if f := readFrame(t, client, dec); f.Type != protocol.TypeInputReady || f.Blocked {
		t.Fatalf("after unlocking: %+v, want INPUT_READY not blocked", f)
	}
}
