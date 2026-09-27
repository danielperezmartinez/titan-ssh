package main

import (
	"fmt"
	"os"
	"path/filepath"
	"syscall"
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

// ensureStateDir creates dir if missing and refuses anything but a real
// directory in its place (a file, or a symlink or junction pointing elsewhere).
// Ownership is not checked: the default path lives in the user's profile,
// whose ACL already keeps other standard users out and which a new directory
// inherits. An owner check would also need care, because an elevated
// administrator's files are owned by the Administrators group, not the user.
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
	if a, ok := fi.Sys().(*syscall.Win32FileAttributeData); ok && a.FileAttributes&syscall.FILE_ATTRIBUTE_REPARSE_POINT != 0 {
		return fmt.Errorf("state dir %s is a symlink or junction", dir)
	}
	return nil
}
