package session

import (
	"fmt"
	"testing"
)

const enter = "\r"

// sizeCommand prints the console size as seen by a program in the session.
const sizeCommand = `powershell -NoProfile -Command "'SZ'+[Console]::WindowWidth+'x'+[Console]::WindowHeight"`

func sizeMarker(cols, rows uint16) string { return fmt.Sprintf("SZ%dx%d", cols, rows) }

// echoCommand returns a command and the output it must produce; the expected
// text never appears in the command itself, so the console's echo of the
// input cannot satisfy the test.
func echoCommand() (cmd, want string) { return "echo TITAN_%OS%", "TITAN_Windows_NT" }

// startTestShell uses cmd.exe so the tests do not depend on the OpenSSH
// DefaultShell configured on the machine.
func startTestShell(t *testing.T, cols, rows uint16) Pty {
	t.Helper()
	p, err := startConPty("cmd.exe", cols, rows)
	if err != nil {
		t.Fatalf("startConPty: %v", err)
	}
	return p
}

func assertShellGone(t *testing.T, p Pty) {
	t.Helper()
	select {
	case <-p.(*conPty).exited:
	default:
		t.Fatal("shell still running after Close")
	}
}
