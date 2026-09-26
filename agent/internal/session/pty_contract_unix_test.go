//go:build linux || darwin || freebsd

package session

import (
	"fmt"
	"os/exec"
	"testing"
)

const enter = "\n"

// sizeCommand prints the terminal size as seen by the shell.
const sizeCommand = `echo "SZ$(stty size | tr ' ' x)"`

func sizeMarker(cols, rows uint16) string { return fmt.Sprintf("SZ%dx%d", rows, cols) }

// echoCommand returns a command and the output it must produce; the expected
// text never appears in the command itself, so the terminal's echo of the
// input cannot satisfy the test.
func echoCommand() (cmd, want string) { return "echo TITAN_$((20+22))", "TITAN_42" }

// startTestShell uses a plain /bin/sh so the tests do not depend on the
// user's shell or rc files.
func startTestShell(t *testing.T, cols, rows uint16) Pty {
	t.Helper()
	p, err := startPty(exec.Command("/bin/sh"), cols, rows)
	if err != nil {
		t.Fatalf("startPty: %v", err)
	}
	return p
}

func assertShellGone(t *testing.T, p Pty) {
	t.Helper()
	if up := p.(*unixPty); up.cmd.ProcessState == nil {
		t.Fatal("shell was not reaped by Close")
	}
}
