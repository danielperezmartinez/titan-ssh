//go:build !linux && !windows

package main

import "errors"

// openScreen has no capturer outside Linux and Windows.
func openScreen() (*screenSource, error) {
	return nil, withCode(codeScreenUnsupported, errors.New("the remote desktop only supports Linux and Windows destinations"))
}

// directInput is false outside Linux: the mouse pad is unsupported.
func directInput(string) (bool, error) { return false, nil }
