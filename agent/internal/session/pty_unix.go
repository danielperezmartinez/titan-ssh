//go:build unix

package session

import (
	"os"
	"os/exec"

	"github.com/creack/pty"
)

// NewPty starts the user's login shell attached to a fresh PTY of the given
// size and returns it behind the Pty seam. It is the PtyFactory used by the
// daemon on the destination.
func NewPty(cols, rows uint16) (Pty, error) {
	shell := os.Getenv("SHELL")
	if shell == "" {
		shell = "/bin/sh"
	}
	// A login, interactive shell so the user's rc files run (prompt, aliases…).
	cmd := exec.Command(shell, "-il")
	f, err := pty.StartWithSize(cmd, &pty.Winsize{Cols: cols, Rows: rows})
	if err != nil {
		return nil, err
	}
	return &unixPty{cmd: cmd, f: f}, nil
}

type unixPty struct {
	cmd *exec.Cmd
	f   *os.File
}

func (p *unixPty) Read(b []byte) (int, error)  { return p.f.Read(b) }
func (p *unixPty) Write(b []byte) (int, error) { return p.f.Write(b) }

func (p *unixPty) Resize(cols, rows uint16) error {
	return pty.Setsize(p.f, &pty.Winsize{Cols: cols, Rows: rows})
}

func (p *unixPty) Close() error {
	err := p.f.Close()
	if p.cmd.Process != nil {
		_ = p.cmd.Process.Kill()
		_, _ = p.cmd.Process.Wait()
	}
	return err
}
