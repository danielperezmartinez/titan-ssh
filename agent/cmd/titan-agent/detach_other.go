//go:build !unix && !windows

package main

import "syscall"

// detachAttr is a no-op on systems without a daemon backend; the lock refuses
// there, so no daemon starts. Present so the command builds everywhere.
func detachAttr() (*syscall.SysProcAttr, error) {
	return &syscall.SysProcAttr{}, nil
}

func spawnFailure(_ *syscall.SysProcAttr, err error) error { return err }
