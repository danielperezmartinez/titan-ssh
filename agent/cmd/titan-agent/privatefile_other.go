//go:build !windows

package main

import "os"

// createPrivateTemp is os.CreateTemp, whose files are 0600 already; the state
// dir around them is checked by ensureStateDir.
func createPrivateTemp(dir, pattern string) (*os.File, error) {
	return os.CreateTemp(dir, pattern)
}
