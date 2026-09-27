//go:build unix && !aix && !(solaris && !illumos)

package main

import (
	"errors"
	"os"

	"golang.org/x/sys/unix"
)

// lockFile takes a whole-file flock(2) on f: Linux, macOS, the BSDs and
// illumos. flock locks belong to the open file description, so even a second
// open in the same process conflicts.
func lockFile(f *os.File) error {
	for {
		err := unix.Flock(int(f.Fd()), unix.LOCK_EX|unix.LOCK_NB)
		switch {
		case errors.Is(err, unix.EINTR):
			continue
		case errors.Is(err, unix.EWOULDBLOCK):
			return errLocked
		default:
			return err
		}
	}
}
