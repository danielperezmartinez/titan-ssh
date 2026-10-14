package main

import (
	"os"
	"path/filepath"
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
	// The shell started in the agent's directory and ping is no shell of its
	// own: the directory is the shell's.
	wd, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	if !strings.EqualFold(r.Cwd, strings.TrimRight(wd, `\`)) && !strings.EqualFold(r.Cwd, wd) {
		t.Fatalf("cwd %q; want %q", r.Cwd, wd)
	}
}

// The directory follows `cd` in cmd.exe, read from the shell's process, and
// is not given while another shell runs inside it: that shell's directory is
// its own.
func TestSessionDirFollowsCmdOnARealConPty(t *testing.T) {
	// Before the registry, so its cleanup runs after the shell has left it.
	dir := t.TempDir()
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
	if !strings.EqualFold(filepath.Base(reg.List()[0].Shell), "cmd.exe") {
		t.Skipf("the default shell here is %s, not cmd.exe", reg.List()[0].Shell)
	}
	waitFor(t, "the shell's first output", func() bool { return !reg.List()[0].LastOutput.IsZero() })
	time.Sleep(time.Second)
	dirOf := func() string {
		table, _ := procmem.Snapshot()
		return sessionFrom(reg.List()[0], sampleWindow{after: table}).Cwd
	}

	if _, err := pty.Write([]byte("cd /d \"" + dir + "\"\r")); err != nil {
		t.Fatal(err)
	}
	waitFor(t, "cmd in the new folder", func() bool { return strings.EqualFold(dirOf(), dir) })

	if _, err := pty.Write([]byte("powershell -NoProfile\r")); err != nil {
		t.Fatal(err)
	}
	waitFor(t, "no folder while PowerShell runs inside", func() bool { return dirOf() == "" })
	if _, err := pty.Write([]byte("exit\r")); err != nil {
		t.Fatal(err)
	}
	waitFor(t, "cmd's folder again", func() bool { return strings.EqualFold(dirOf(), dir) })
}
