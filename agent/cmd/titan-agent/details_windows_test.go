package main

import (
	"strings"
	"testing"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/procmem"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/session"
)

// On a real ConPTY the status describes the session: the shell, its size,
// and the program running in it, guessed from the process tree since ConPTY
// has no foreground process group.
func TestSessionDetailsOnARealConPty(t *testing.T) {
	var pty session.Pty
	reg := session.NewRegistry(func(cols, rows uint16) (session.Pty, error) {
		p, err := session.NewPty(cols, rows)
		pty = p
		return p, err
	}, 1<<20)
	t.Cleanup(func() { reg.CloseAll() })
	if _, _, err := reg.AttachOrCreate("win", 100, 30); err != nil {
		t.Fatal(err)
	}
	// Let the shell start before typing: a PTY drops earlier input.
	waitFor(t, "the shell's first output", func() bool { return !reg.List()[0].LastOutput.IsZero() })
	time.Sleep(time.Second)
	if _, err := pty.Write([]byte("ping -n 30 127.0.0.1\r")); err != nil {
		t.Fatal(err)
	}

	var r sessionReport
	waitFor(t, "ping in front", func() bool {
		before, _ := procmem.Snapshot()
		start := time.Now()
		time.Sleep(50 * time.Millisecond)
		after, _ := procmem.Snapshot()
		r = sessionFrom(reg.List()[0], sampleWindow{before: before, after: after, elapsed: time.Since(start)})
		return strings.EqualFold(r.Foreground, "ping.exe")
	})
	t.Logf("details: %+v", r)
	if !strings.HasSuffix(strings.ToLower(r.Shell), ".exe") || r.ShellPID == 0 {
		t.Fatalf("shell: %q (%d)", r.Shell, r.ShellPID)
	}
	if r.Cols != 100 || r.Rows != 30 {
		t.Fatalf("size %dx%d", r.Cols, r.Rows)
	}
	if r.ForegroundPID == 0 || r.ForegroundPID == r.ShellPID {
		t.Fatalf("foreground PID %d (shell %d)", r.ForegroundPID, r.ShellPID)
	}
	if r.CPUPercent == nil || r.MemoryBytes == nil || r.LastOutputMs == 0 {
		t.Fatalf("measures missing: %+v", r)
	}
	if r.Cwd != "" {
		t.Fatalf("Windows does not report a cwd, got %q", r.Cwd)
	}
}
