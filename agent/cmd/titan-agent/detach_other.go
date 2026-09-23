//go:build !unix

package main

import "syscall"

// detachAttr is a no-op off Unix; titan-agent's daemon only runs on the (Unix)
// destination. Present so the command builds on other hosts (e.g. the dev box).
func detachAttr() *syscall.SysProcAttr {
	return &syscall.SysProcAttr{}
}
