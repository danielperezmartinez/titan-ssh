//go:build unix

package main

import (
	"fmt"
	"os"
	"path/filepath"
	"syscall"
)

// defaultStateDir is $XDG_STATE_HOME/titan-ssh, or ~/.local/state/titan-ssh.
// Not $XDG_RUNTIME_DIR: systemd removes /run/user/<uid> at the last logout,
// which would strand a live daemon. Not /tmp either, which cleaners empty. A
// relative $XDG_STATE_HOME is ignored, as the XDG spec requires.
func defaultStateDir() (string, error) {
	if dir := os.Getenv("XDG_STATE_HOME"); filepath.IsAbs(dir) {
		return filepath.Join(dir, stateDirName), nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, ".local", "state", stateDirName), nil
}

// ensureStateDir creates dir (0700) if missing and refuses to use it unless it
// is a real directory (not a symlink) owned by the current user with no group
// or other access. It holds the token that lets anyone reach the daemon's
// shells, so a directory another user owns or can enter would let them read
// the token, or plant a fake state file that points the front at their own
// listener and see every keystroke (passwords included). The default path is
// created here as 0700, so this only rejects hostile or custom dirs. The
// daemon, the front and --stop all call it before touching the state files.
func ensureStateDir(dir string) error {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	fi, err := os.Lstat(dir)
	if err != nil {
		return err
	}
	if !fi.IsDir() {
		return fmt.Errorf("state dir %s is not a directory", dir)
	}
	st, ok := fi.Sys().(*syscall.Stat_t)
	if !ok {
		return fmt.Errorf("state dir %s: cannot read owner", dir)
	}
	if uid := os.Getuid(); int(st.Uid) != uid {
		return fmt.Errorf("state dir %s is owned by uid %d, not %d", dir, st.Uid, uid)
	}
	if fi.Mode().Perm()&0o077 != 0 {
		return fmt.Errorf("state dir %s is accessible by group/others (%v)", dir, fi.Mode().Perm())
	}
	return nil
}
