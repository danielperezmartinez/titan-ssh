//go:build !unix && !windows

package main

import (
	"errors"
	"os"
	"path/filepath"
)

// defaultStateDir and ensureStateDir keep the command building on systems
// without a daemon backend; lockFile refuses there, so no daemon starts.
func defaultStateDir() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, "."+stateDirName), nil
}

func ensureStateDir(dir string) error { return os.MkdirAll(dir, 0o700) }

func lockFile(f *os.File) error { return errors.New("file locking is not supported on this system") }
