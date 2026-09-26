//go:build linux || darwin || freebsd

package session

import (
	"errors"
	"io"
	"os"
	"os/exec"
	"syscall"

	"golang.org/x/sys/unix"
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
	return startPty(exec.Command(shell, "-il"), cols, rows)
}

// startPty runs cmd as the session leader of a new PTY. The master/slave pair
// comes from the per-OS openPty (pty_linux.go, pty_darwin.go, pty_freebsd.go).
func startPty(cmd *exec.Cmd, cols, rows uint16) (Pty, error) {
	master, slave, err := openPty()
	if err != nil {
		return nil, ptyError(CodePty, err)
	}
	// The slave is only needed by the child; the parent keeps the master.
	defer slave.Close()
	if err := setWinsize(master, cols, rows); err != nil {
		_ = master.Close()
		return nil, ptyError(CodePty, err)
	}
	cmd.Stdin, cmd.Stdout, cmd.Stderr = slave, slave, slave
	// New session with the slave as its controlling terminal. Ctty is a
	// descriptor number in the child: 0 is its stdin, the slave.
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true, Setctty: true, Ctty: 0}
	if err := cmd.Start(); err != nil {
		_ = master.Close()
		return nil, ptyError(CodePty, err)
	}
	return &unixPty{cmd: cmd, master: master}, nil
}

type unixPty struct {
	cmd    *exec.Cmd
	master *os.File
}

// Read returns io.EOF once the shell (and every other holder of the slave) has
// gone: Linux reports that as EIO on the master, and Session.pump treats any
// error as the end of the PTY anyway.
func (p *unixPty) Read(b []byte) (int, error) {
	n, err := p.master.Read(b)
	if errors.Is(err, syscall.EIO) {
		err = io.EOF
	}
	return n, err
}

func (p *unixPty) Write(b []byte) (int, error) { return p.master.Write(b) }

func (p *unixPty) Resize(cols, rows uint16) error { return setWinsize(p.master, cols, rows) }

func (p *unixPty) Close() error {
	err := p.master.Close()
	if p.cmd.Process != nil {
		_ = p.cmd.Process.Kill()
		_ = p.cmd.Wait()
	}
	return err
}

func setWinsize(f *os.File, cols, rows uint16) error {
	return withFd(f, func(fd int) error {
		return unix.IoctlSetWinsize(fd, unix.TIOCSWINSZ, &unix.Winsize{Row: rows, Col: cols})
	})
}

// withFd runs fn on f's descriptor without f.Fd(), which would switch a
// pollable file back to blocking mode.
func withFd(f *os.File, fn func(fd int) error) error {
	rc, err := f.SyscallConn()
	if err != nil {
		return err
	}
	var opErr error
	if err := rc.Control(func(fd uintptr) { opErr = fn(int(fd)) }); err != nil {
		return err
	}
	return opErr
}
