//go:build !linux

package x11

import "errors"

// attach: MIT-SHM is only used on Linux.
func (s *ShmImage) attach() error {
	return errors.New("x11: MIT-SHM is only used on Linux")
}

func (s *ShmImage) detach() error { return nil }
