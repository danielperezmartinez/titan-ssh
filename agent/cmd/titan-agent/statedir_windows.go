package main

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"syscall"

	"golang.org/x/sys/windows"
)

// defaultStateDir is %LOCALAPPDATA%\titan-ssh (not os.UserCacheDir, which is
// meant for disposable data), falling back to the profile's AppData\Local.
func defaultStateDir() (string, error) {
	if dir := os.Getenv("LOCALAPPDATA"); filepath.IsAbs(dir) {
		return filepath.Join(dir, stateDirName), nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, "AppData", "Local", stateDirName), nil
}

// ensureStateDir creates dir if missing, private to the user (acl_windows.go),
// and refuses to use it unless it is a real directory (not a file, nor a
// symlink or junction pointing elsewhere) that only the user, SYSTEM and
// Administrators can reach. As on Unix, a dir someone else can read or write
// would let them take the token, or plant a state file that points the front
// at their own listener. The daemon, the front and every other mode call it
// before touching the state files.
func ensureStateDir(dir string) error {
	dir = filepath.Clean(dir)
	if err := os.MkdirAll(filepath.Dir(dir), 0o700); err != nil {
		return err
	}
	if err := createPrivateDir(dir); err != nil && !errors.Is(err, windows.ERROR_ALREADY_EXISTS) {
		return &os.PathError{Op: "mkdir", Path: dir, Err: err}
	}
	fi, err := os.Lstat(dir)
	if err != nil {
		return err
	}
	if !fi.IsDir() {
		return fmt.Errorf("state dir %s is not a directory", dir)
	}
	if a, ok := fi.Sys().(*syscall.Win32FileAttributeData); ok && a.FileAttributes&syscall.FILE_ATTRIBUTE_REPARSE_POINT != 0 {
		return fmt.Errorf("state dir %s is a symlink or junction", dir)
	}
	return checkPrivateDir(dir)
}
