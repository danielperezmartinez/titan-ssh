//go:build unix

package portal

import "golang.org/x/sys/unix"

func closeFD(fd int) { _ = unix.Close(fd) }
