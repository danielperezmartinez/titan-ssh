package session

import (
	"bytes"
	"errors"
	"os"
	"syscall"
	"unsafe"

	"golang.org/x/sys/unix"
)

// openPty opens a master on /dev/ptmx, reads its slave name, grants and
// unlocks it, and opens the slave.
func openPty() (master, slave *os.File, err error) {
	// Opened without the poller (kqueue does not handle ptys well on macOS):
	// reads block in their own thread, and Close ends them by killing the
	// shell, which closes the slave.
	fd, err := unix.Open("/dev/ptmx", unix.O_RDWR|unix.O_NOCTTY|unix.O_CLOEXEC, 0)
	if err != nil {
		return nil, nil, err
	}
	master = os.NewFile(uintptr(fd), "/dev/ptmx")
	name, err := ptsName(fd)
	if err == nil {
		err = unix.IoctlSetInt(fd, unix.TIOCPTYGRANT, 0)
	}
	if err == nil {
		err = unix.IoctlSetInt(fd, unix.TIOCPTYUNLK, 0)
	}
	if err == nil {
		slave, err = os.OpenFile(name, os.O_RDWR|syscall.O_NOCTTY, 0)
	}
	if err != nil {
		_ = master.Close()
		return nil, nil, err
	}
	return master, slave, nil
}

// ptsName reads the slave's path with TIOCPTYGNAME, which fills a 128-byte
// buffer (the size encoded in the request). x/sys/unix has no wrapper that
// passes an arbitrary buffer, hence the raw ioctl.
func ptsName(fd int) (string, error) {
	var buf [128]byte
	if _, _, errno := syscall.Syscall(syscall.SYS_IOCTL, uintptr(fd), uintptr(unix.TIOCPTYGNAME), uintptr(unsafe.Pointer(&buf[0]))); errno != 0 {
		return "", errno
	}
	n := bytes.IndexByte(buf[:], 0)
	if n < 0 {
		return "", errors.New("TIOCPTYGNAME name not NUL-terminated")
	}
	return string(buf[:n]), nil
}
