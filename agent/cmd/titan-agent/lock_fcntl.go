//go:build aix || (solaris && !illumos)

package main

import (
	"errors"
	"io"
	"os"

	"golang.org/x/sys/unix"
)

// lockFile takes a whole-file POSIX record lock on f: AIX and Solaris, which
// lack flock(2). Unlike flock, these locks belong to the process, so a second
// lock from the same process succeeds; the daemon only ever takes one, and the
// front and --stop probe from their own processes.
func lockFile(f *os.File) error {
	lk := unix.Flock_t{Type: unix.F_WRLCK, Whence: io.SeekStart} // Start 0, Len 0: the whole file
	for {
		err := unix.FcntlFlock(f.Fd(), unix.F_SETLK, &lk)
		switch {
		case errors.Is(err, unix.EINTR):
			continue
		case errors.Is(err, unix.EAGAIN), errors.Is(err, unix.EACCES):
			return errLocked
		default:
			return err
		}
	}
}
