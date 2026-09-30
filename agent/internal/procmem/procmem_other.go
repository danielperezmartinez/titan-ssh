//go:build !linux && !darwin && !freebsd && !windows

package procmem

func list() ([]proc, error) { return nil, ErrUnsupported }

// Cwd is not available on this system.
func Cwd(int) string { return "" }
