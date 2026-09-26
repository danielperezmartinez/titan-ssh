//go:build !linux && !darwin && !freebsd && !windows

package session

import "errors"

// NewPty is unsupported on systems without a PTY backend (ADR-0009 covers
// Linux, macOS, FreeBSD and Windows). This stub keeps the package building
// there and reports the E_PTY error-contract code.
func NewPty(cols, rows uint16) (Pty, error) {
	return nil, ptyError(CodePty, errors.New("PTY not supported on this platform"))
}
