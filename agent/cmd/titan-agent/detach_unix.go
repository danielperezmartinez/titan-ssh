//go:build unix

package main

import "syscall"

// detachAttr puts the spawned daemon in its own session (setsid), so it is not
// killed when the front's SSH exec channel and process group go away.
func detachAttr() *syscall.SysProcAttr {
	return &syscall.SysProcAttr{Setsid: true}
}
