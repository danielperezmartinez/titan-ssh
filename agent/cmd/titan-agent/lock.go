package main

import (
	"errors"
	"os"
	"path/filepath"
)

// errLocked reports that another process holds the daemon lock: a daemon is
// already running for this user.
var errLocked = errors.New("the daemon lock is held by another process")

// lockDaemon takes the exclusive, non-blocking single-instance lock on
// dir/agent.lock and returns the open file that holds it (ADR-0009 §3). The
// lock lasts while the file stays open, and the kernel drops it when the
// process dies, however it dies (kill -9 or a crash included), so there are no
// stale locks or PID files. It returns errLocked if a live daemon holds it.
//
// os.OpenFile opens close-on-exec on Unix and non-inheritable on Windows, so
// the shells the daemon starts never inherit the lock and cannot keep it after
// the daemon is gone.
func lockDaemon(dir string) (*os.File, error) {
	return lockNamed(dir, lockFileName)
}

// lockNamed is lockDaemon for the lock file called name (agent.lock, or the
// desktop helper's desktop.lock).
func lockNamed(dir, name string) (*os.File, error) {
	f, err := os.OpenFile(filepath.Join(dir, name), os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return nil, err
	}
	if err := lockFile(f); err != nil {
		f.Close()
		return nil, err
	}
	return f, nil
}

// lockHeld reports whether a live daemon holds the lock in dir, by taking and
// at once releasing it. An error means the OS refuses the lock altogether.
func lockHeld(dir string) (bool, error) {
	return lockNamedHeld(dir, lockFileName)
}

// lockNamedHeld is lockHeld for the lock file called name.
func lockNamedHeld(dir, name string) (bool, error) {
	f, err := lockNamed(dir, name)
	if errors.Is(err, errLocked) {
		return true, nil
	}
	if err != nil {
		return false, err
	}
	return false, f.Close()
}
