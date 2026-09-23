package main

import (
	"errors"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"time"
)

// runFront splices this process's stdio (the SSH exec channel) to the daemon's
// Unix socket, starting the daemon detached on first use. It returns when either
// side closes, which is a normal client disconnect — the daemon keeps the
// session alive for the next attach.
func runFront(sockPath string) error {
	// Refuse a socket dir someone else controls: a fake daemon there would see
	// every keystroke (passwords included) the client sends.
	if err := ensureSocketDir(filepath.Dir(sockPath)); err != nil {
		return err
	}
	conn, err := dialOrSpawn(sockPath)
	if err != nil {
		return err
	}
	defer conn.Close()

	done := make(chan struct{}, 2)
	go func() { _, _ = io.Copy(conn, os.Stdin); done <- struct{}{} }()  // client -> daemon
	go func() { _, _ = io.Copy(os.Stdout, conn); done <- struct{}{} }() // daemon -> client
	<-done
	return nil
}

// dialOrSpawn connects to the daemon, launching it (detached, own session) if it
// is not yet listening, then waiting briefly for the socket to appear.
func dialOrSpawn(sockPath string) (net.Conn, error) {
	if c, err := net.Dial("unix", sockPath); err == nil {
		return c, nil
	}
	exe, err := os.Executable()
	if err != nil {
		return nil, err
	}
	cmd := exec.Command(exe, "--daemon", "--socket", sockPath)
	cmd.SysProcAttr = detachAttr()
	// Detach stdio so the daemon does not hold the exec channel open.
	cmd.Stdin, cmd.Stdout, cmd.Stderr = nil, nil, nil
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	_ = cmd.Process.Release()

	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if c, err := net.Dial("unix", sockPath); err == nil {
			return c, nil
		}
		time.Sleep(50 * time.Millisecond)
	}
	return nil, errors.New("daemon did not come up on " + sockPath)
}
