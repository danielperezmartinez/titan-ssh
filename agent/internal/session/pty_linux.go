package session

import (
	"os"
	"strconv"
	"syscall"

	"golang.org/x/sys/unix"
)

// openPty opens a master on /dev/ptmx, unlocks it and opens its slave
// /dev/pts/<n>.
func openPty() (master, slave *os.File, err error) {
	master, err = os.OpenFile("/dev/ptmx", os.O_RDWR|syscall.O_NOCTTY, 0)
	if err != nil {
		return nil, nil, err
	}
	var n uint32
	err = withFd(master, func(fd int) error {
		if err := unix.IoctlSetPointerInt(fd, unix.TIOCSPTLCK, 0); err != nil {
			return err
		}
		var err error
		n, err = unix.IoctlGetUint32(fd, unix.TIOCGPTN)
		return err
	})
	if err == nil {
		slave, err = os.OpenFile("/dev/pts/"+strconv.FormatUint(uint64(n), 10), os.O_RDWR|syscall.O_NOCTTY, 0)
	}
	if err != nil {
		_ = master.Close()
		return nil, nil, err
	}
	return master, slave, nil
}
