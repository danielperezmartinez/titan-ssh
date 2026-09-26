package session

import (
	"os"
	"strconv"
	"syscall"

	"golang.org/x/sys/unix"
)

// openPty opens a master with posix_openpt(2), gets its unit number with
// TIOCGPTN and opens the slave /dev/pts/<n>. FreeBSD needs no grant or unlock
// step: grantpt/unlockpt are no-ops in its libc.
func openPty() (master, slave *os.File, err error) {
	r, _, errno := unix.Syscall(unix.SYS_POSIX_OPENPT, uintptr(unix.O_RDWR|unix.O_NOCTTY|unix.O_CLOEXEC), 0, 0)
	if errno != 0 {
		return nil, nil, errno
	}
	fd := int(r)
	master = os.NewFile(uintptr(fd), "/dev/ptmx")
	n, err := unix.IoctlGetInt(fd, unix.TIOCGPTN)
	if err == nil {
		slave, err = os.OpenFile("/dev/pts/"+strconv.Itoa(n), os.O_RDWR|syscall.O_NOCTTY, 0)
	}
	if err != nil {
		_ = master.Close()
		return nil, nil, err
	}
	return master, slave, nil
}
