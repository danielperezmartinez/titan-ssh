//go:build unix

package main

import (
	"fmt"
	"net"
	"os"
	"syscall"
)

// ensureSocketDir creates dir (0700) if missing and refuses to use it unless it
// is a real directory (not a symlink) owned by the current user with no group
// or other access. The daemon hands out shells over this socket, so a directory
// another user owns or can enter (e.g. a pre-created /tmp/titan-ssh-<uid>)
// would let them reach the daemon, intercept the front or plant a fake daemon.
// The directory, not the socket's own mode, is the gate: some BSDs ignore
// socket permissions on connect. The default paths ($XDG_RUNTIME_DIR, or the
// fallback created here) are 0700, so this only rejects hostile or custom dirs.
// Both the daemon and the front call it before touching the socket.
func ensureSocketDir(dir string) error {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	fi, err := os.Lstat(dir)
	if err != nil {
		return err
	}
	if !fi.IsDir() {
		return fmt.Errorf("socket dir %s is not a directory", dir)
	}
	st, ok := fi.Sys().(*syscall.Stat_t)
	if !ok {
		return fmt.Errorf("socket dir %s: cannot read owner", dir)
	}
	if uid := os.Getuid(); int(st.Uid) != uid {
		return fmt.Errorf("socket dir %s is owned by uid %d, not %d", dir, st.Uid, uid)
	}
	if fi.Mode().Perm()&0o077 != 0 {
		return fmt.Errorf("socket dir %s is accessible by group/others (%v)", dir, fi.Mode().Perm())
	}
	return nil
}

// listenPrivate listens on a Unix socket that is 0600 from the moment it
// exists: the umask is tightened around bind, so there is no window between
// Listen and a later Chmod in which another user could connect. The daemon
// calls it once at startup, before any other goroutine creates files, so the
// process-wide umask change is safe.
func listenPrivate(sockPath string) (net.Listener, error) {
	old := syscall.Umask(0o177)
	ln, err := net.Listen("unix", sockPath)
	syscall.Umask(old)
	if err != nil {
		return nil, err
	}
	if err := os.Chmod(sockPath, 0o600); err != nil {
		ln.Close()
		return nil, err
	}
	return ln, nil
}
