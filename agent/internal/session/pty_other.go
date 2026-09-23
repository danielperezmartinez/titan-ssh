//go:build !unix

package session

import "errors"

// NewPty is unsupported off Unix; titan-agent only runs on the (Unix) SSH
// destination. This stub keeps the package building and testable on other
// hosts (e.g. the Windows dev machine) without pulling in a PTY dependency.
func NewPty(cols, rows uint16) (Pty, error) {
	return nil, errors.New("session: PTY not supported on this platform")
}
