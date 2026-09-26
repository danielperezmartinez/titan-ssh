//go:build linux || darwin || freebsd || windows

package session

import (
	"bytes"
	"errors"
	"io"
	"strings"
	"sync"
	"testing"
	"time"
)

// Contract tests run against the real PTY backend of the host OS. The shell
// and its commands come from pty_contract_{unix,windows}_test.go.

const ptyTimeout = 15 * time.Second

// ptyOutput drains a Pty in the background, as Session.pump does (ConPTY needs
// its output read to make progress), and lets tests wait on what arrived.
type ptyOutput struct {
	mu   sync.Mutex
	buf  bytes.Buffer
	err  error
	done chan struct{}
}

func drain(p Pty) *ptyOutput {
	o := &ptyOutput{done: make(chan struct{})}
	go func() {
		defer close(o.done)
		b := make([]byte, 4096)
		for {
			n, err := p.Read(b)
			o.mu.Lock()
			o.buf.Write(b[:n])
			if err != nil {
				o.err = err
			}
			o.mu.Unlock()
			if err != nil {
				return
			}
		}
	}()
	return o
}

// waitFor waits until want appears in the output after offset from, and
// returns the offset just past it.
func (o *ptyOutput) waitFor(t *testing.T, from int, want string) int {
	t.Helper()
	deadline := time.Now().Add(ptyTimeout)
	for time.Now().Before(deadline) {
		o.mu.Lock()
		out := o.buf.String()
		o.mu.Unlock()
		if i := strings.Index(out[from:], want); i >= 0 {
			return from + i + len(want)
		}
		select {
		case <-o.done:
			t.Fatalf("PTY closed before %q appeared; output: %q", want, out)
		case <-time.After(20 * time.Millisecond):
		}
	}
	o.mu.Lock()
	defer o.mu.Unlock()
	t.Fatalf("timed out waiting for %q; output: %q", want, o.buf.String())
	return 0
}

// waitEnd waits for the reader to stop and returns the error that stopped it.
func (o *ptyOutput) waitEnd(t *testing.T) error {
	t.Helper()
	select {
	case <-o.done:
	case <-time.After(ptyTimeout):
		t.Fatal("Read did not return after the shell went away")
	}
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.err
}

func run(t *testing.T, p Pty, line string) {
	t.Helper()
	if _, err := p.Write([]byte(line + enter)); err != nil {
		t.Fatalf("write %q: %v", line, err)
	}
}

func TestPtyEcho(t *testing.T) {
	p := startTestShell(t, 80, 24)
	defer p.Close()
	out := drain(p)
	cmd, want := echoCommand()
	run(t, p, cmd)
	out.waitFor(t, 0, want)
}

func TestPtyResize(t *testing.T) {
	p := startTestShell(t, 80, 24)
	defer p.Close()
	out := drain(p)
	run(t, p, sizeCommand)
	at := out.waitFor(t, 0, sizeMarker(80, 24))
	if err := p.Resize(100, 30); err != nil {
		t.Fatalf("Resize: %v", err)
	}
	run(t, p, sizeCommand)
	out.waitFor(t, at, sizeMarker(100, 30))
}

func TestPtyEOFWhenShellExits(t *testing.T) {
	p := startTestShell(t, 80, 24)
	defer p.Close()
	out := drain(p)
	run(t, p, "exit")
	if err := out.waitEnd(t); !errors.Is(err, io.EOF) {
		t.Fatalf("Read after exit = %v, want io.EOF", err)
	}
}

func TestPtyCloseEndsLiveShell(t *testing.T) {
	p := startTestShell(t, 80, 24)
	out := drain(p)
	cmd, want := echoCommand()
	run(t, p, cmd)
	out.waitFor(t, 0, want) // the shell is up and serving
	closed := make(chan error, 1)
	go func() { closed <- p.Close() }()
	select {
	case <-closed:
	case <-time.After(ptyTimeout):
		t.Fatal("Close blocked with a live shell")
	}
	out.waitEnd(t)
	assertShellGone(t, p)
}

// The daemon's own factory: it starts the default shell and can be closed.
func TestNewPtyStartsDefaultShell(t *testing.T) {
	p, err := NewPty(80, 24)
	if err != nil {
		t.Fatalf("NewPty: %v", err)
	}
	out := drain(p)
	if err := p.Close(); err != nil {
		t.Errorf("Close: %v", err)
	}
	out.waitEnd(t)
	assertShellGone(t, p)
}
