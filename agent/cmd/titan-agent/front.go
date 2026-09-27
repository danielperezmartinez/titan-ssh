package main

import (
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"time"
)

// How the front waits for a daemon it launched. Vars so tests can shorten them.
var (
	spawnWait    = 10 * time.Second        // give up (E_DAEMON_START) after this
	pollInterval = 50 * time.Millisecond   // re-read the state file this often
	respawnGap   = 1500 * time.Millisecond // launch again if still no daemon after this
)

// runFront splices this process's stdio (the SSH exec channel) to the user's
// daemon, starting the daemon detached on first use. It returns when either
// side closes, which is a normal client disconnect — the daemon keeps the
// session alive for the next attach.
func runFront(stateDir string) error {
	// Refuse a state dir someone else controls: a fake state file there would
	// point the front at their listener, which would see every keystroke
	// (passwords included) the client sends.
	if err := ensureStateDir(stateDir); err != nil {
		return withCode(codeStateDir, err)
	}
	conn, err := dialOrSpawn(stateDir, func() error { return spawnDaemon(stateDir) })
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

// dialOrSpawn connects to the daemon published in stateDir. While none
// answers, it launches one with spawn whenever the lock shows no daemon is
// alive, and re-reads the state file on every attempt: a new daemon publishes a
// new port and token. Errors carry their error-contract code.
func dialOrSpawn(stateDir string, spawn func() error) (net.Conn, error) {
	deadline := time.Now().Add(spawnWait)
	var lastSpawn time.Time
	for {
		st, err := readState(stateDir)
		if err == nil {
			var conn net.Conn
			if conn, err = dialDaemon(st); err == nil {
				return conn, nil
			}
		}
		if time.Now().After(deadline) {
			return nil, frontGiveUp(stateDir, err)
		}
		// No answer. Probe the lock, but not right after a launch: the probe
		// takes the lock for an instant and would make a daemon that is just
		// starting back off.
		if time.Since(lastSpawn) >= respawnGap {
			held, lerr := lockHeld(stateDir)
			if lerr != nil {
				return nil, withCode(codeLock, lerr)
			}
			if !held {
				if serr := spawn(); serr != nil {
					return nil, withCode(codeDaemonStart, serr)
				}
				lastSpawn = time.Now()
			}
		}
		time.Sleep(pollInterval)
	}
}

// frontGiveUp classifies the last failure once spawnWait is over. A live daemon
// (the lock is held) that refuses the token, or whose state file cannot be
// read, is an authentication problem; anything else means no daemon came up.
func frontGiveUp(stateDir string, last error) error {
	held, _ := lockHeld(stateDir)
	if held && !errors.Is(last, fs.ErrNotExist) && !isNetError(last) {
		return withCode(codeAuth, last)
	}
	return withCode(codeDaemonStart, fmt.Errorf("no daemon answered within %v: %w", spawnWait, last))
}

func isNetError(err error) bool {
	var ne net.Error
	return errors.As(err, &ne)
}

// spawnDaemon launches this binary as the daemon for stateDir, detached from
// the front's session so it outlives the SSH exec channel.
func spawnDaemon(stateDir string) error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	cmd := exec.Command(exe, "--daemon", "--state-dir", stateDir)
	cmd.SysProcAttr = detachAttr()
	// Detach stdio so the daemon does not hold the exec channel open.
	cmd.Stdin, cmd.Stdout, cmd.Stderr = nil, nil, nil
	if err := cmd.Start(); err != nil {
		return err
	}
	// Reap it if it exits while the front lives (e.g. it lost the lock race).
	go func() { _ = cmd.Wait() }()
	return nil
}

// stopDaemon is --stop: it ends the user's daemon, if one runs, and removes its
// state file. It first authenticates against the published state, which proves
// that record is the live daemon's, so the PID it kills is the daemon and never
// a recycled one. Used to uninstall and to clean up after tests.
func stopDaemon(stateDir string) error {
	if err := ensureStateDir(stateDir); err != nil {
		return withCode(codeStateDir, err)
	}
	stateFile := filepath.Join(stateDir, stateFileName)
	if st, err := readState(stateDir); err == nil {
		if conn, err := dialDaemon(st); err == nil {
			conn.Close()
			p, err := os.FindProcess(st.PID)
			if err != nil {
				return err
			}
			if err := p.Kill(); err != nil {
				return err
			}
			if err := waitLockFree(stateDir, 5*time.Second); err != nil {
				return err
			}
			return removeIfExists(stateFile)
		}
	}
	held, err := lockHeld(stateDir)
	if err != nil {
		return withCode(codeLock, err)
	}
	if held {
		return errors.New("a daemon holds the lock but does not answer on its published port")
	}
	return removeIfExists(stateFile) // nothing running; drop a stale record
}

// waitLockFree polls until no process holds the daemon lock.
func waitLockFree(stateDir string, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	for {
		held, err := lockHeld(stateDir)
		if err != nil || !held {
			return err
		}
		if time.Now().After(deadline) {
			return errors.New("the daemon did not exit in time")
		}
		time.Sleep(20 * time.Millisecond)
	}
}

func removeIfExists(path string) error {
	if err := os.Remove(path); err != nil && !errors.Is(err, fs.ErrNotExist) {
		return err
	}
	return nil
}
