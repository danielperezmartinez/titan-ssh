//go:build unix

package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestEnsureSocketDirCreatesPrivateDir(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "a", "titan")
	if err := ensureSocketDir(dir); err != nil {
		t.Fatalf("fresh dir should be accepted: %v", err)
	}
	fi, err := os.Stat(dir)
	if err != nil {
		t.Fatal(err)
	}
	if fi.Mode().Perm()&0o077 != 0 {
		t.Fatalf("dir should not be accessible to group/others, got %v", fi.Mode().Perm())
	}
}

func TestEnsureSocketDirRejectsSharedDir(t *testing.T) {
	tests := []struct {
		name string
		mode os.FileMode
	}{
		{name: "world-writable", mode: 0o777},
		{name: "group-readable", mode: 0o750},
		{name: "world-traversable", mode: 0o701},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			dir := filepath.Join(t.TempDir(), "open")
			if err := os.Mkdir(dir, 0o700); err != nil {
				t.Fatal(err)
			}
			if err := os.Chmod(dir, tt.mode); err != nil {
				t.Fatal(err)
			}
			if err := ensureSocketDir(dir); err == nil {
				t.Fatalf("dir with mode %v must be rejected", tt.mode)
			}
		})
	}
}

func TestEnsureSocketDirRejectsSymlink(t *testing.T) {
	base := t.TempDir()
	target := filepath.Join(base, "real")
	if err := os.Mkdir(target, 0o700); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(base, "link")
	if err := os.Symlink(target, link); err != nil {
		t.Fatal(err)
	}
	if err := ensureSocketDir(link); err == nil {
		t.Fatal("symlinked socket dir must be rejected")
	}
}

func TestListenPrivateCreatesOwnerOnlySocket(t *testing.T) {
	sock := filepath.Join(t.TempDir(), "t.sock")
	ln, err := listenPrivate(sock)
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	fi, err := os.Lstat(sock)
	if err != nil {
		t.Fatal(err)
	}
	if fi.Mode()&os.ModeSocket == 0 || fi.Mode().Perm() != 0o600 {
		t.Fatalf("expected a 0600 socket, got %v", fi.Mode())
	}
}
