//go:build linux || darwin || freebsd

package session

import (
	"testing"
	"time"
)

// Foreground follows the terminal's foreground process group: the shell at
// its prompt, then the job it runs.
func TestForegroundFollowsTheRunningJob(t *testing.T) {
	p := startTestShell(t, 80, 24)
	out := drain(p)
	t.Cleanup(func() { _ = p.Close() })
	up := p.(*unixPty)
	if up.Shell() != "/bin/sh" {
		t.Fatalf("Shell = %q", up.Shell())
	}

	cmd, want := echoCommand()
	if _, err := p.Write([]byte(cmd + enter)); err != nil {
		t.Fatal(err)
	}
	out.waitFor(t, 0, want)
	waitForeground(t, up, "the shell at its prompt", func(pg int) bool { return pg == up.Pid() })

	if _, err := p.Write([]byte("sleep 30" + enter)); err != nil {
		t.Fatal(err)
	}
	waitForeground(t, up, "the sleep job", func(pg int) bool { return pg > 0 && pg != up.Pid() })
}

func waitForeground(t *testing.T, p *unixPty, what string, ok func(int) bool) {
	t.Helper()
	deadline := time.Now().Add(ptyTimeout)
	for {
		pg := p.Foreground()
		if ok(pg) {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("foreground never became %s (last %d, shell %d)", what, pg, p.Pid())
		}
		time.Sleep(20 * time.Millisecond)
	}
}
