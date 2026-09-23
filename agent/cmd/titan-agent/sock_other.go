//go:build !unix

package main

import (
	"net"
	"os"
)

// ensureSocketDir only creates dir off Unix; the ownership/permission checks
// live in sock_unix.go, where the daemon actually runs. Present so the command
// builds on other hosts (e.g. the dev box).
func ensureSocketDir(dir string) error {
	return os.MkdirAll(dir, 0o700)
}

// listenPrivate is a plain listen off Unix (see sock_unix.go).
func listenPrivate(sockPath string) (net.Listener, error) {
	return net.Listen("unix", sockPath)
}
