//go:build !linux && !darwin && !freebsd && !windows

package procmem

func list() ([]proc, error) { return nil, ErrUnsupported }
