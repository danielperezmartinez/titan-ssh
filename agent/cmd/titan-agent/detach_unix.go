//go:build unix

package main

import "syscall"

// detachAttr puts the spawned daemon in its own session (setsid), so it is not
// killed when the front's SSH exec channel and process group go away.
func detachAttr() (*syscall.SysProcAttr, error) {
	return &syscall.SysProcAttr{Setsid: true}, nil
}

// spawnFailure passes a failed daemon launch through unchanged.
func spawnFailure(_ *syscall.SysProcAttr, err error) error { return err }
