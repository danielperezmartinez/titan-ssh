//go:build !linux

package main

import "errors"

// openScreen has no capturer outside Linux X11 yet: Windows and Wayland come
// with their own tasks (ADR-0029 §5).
func openScreen() (*screenSource, error) {
	return nil, withCode(codeScreenUnsupported, errors.New("the remote desktop only supports Linux X11 destinations for now"))
}

// directInput is false outside Linux: the mouse pad goes through the desktop
// helper (Windows) or is unsupported.
func directInput(string) (bool, error) { return false, nil }
