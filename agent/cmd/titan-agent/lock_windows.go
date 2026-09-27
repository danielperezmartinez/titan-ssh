package main

import (
	"errors"
	"os"

	"golang.org/x/sys/windows"
)

// lockFile takes an exclusive LockFileEx lock on the first byte of f without
// waiting. The lock belongs to the handle, so even a second open in the same
// process conflicts.
func lockFile(f *os.File) error {
	var ol windows.Overlapped // offset 0
	err := windows.LockFileEx(windows.Handle(f.Fd()), windows.LOCKFILE_EXCLUSIVE_LOCK|windows.LOCKFILE_FAIL_IMMEDIATELY, 0, 1, 0, &ol)
	if errors.Is(err, windows.ERROR_LOCK_VIOLATION) {
		return errLocked
	}
	return err
}
