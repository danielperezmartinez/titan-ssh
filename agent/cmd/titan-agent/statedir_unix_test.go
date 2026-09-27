//go:build unix

package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestDefaultStateDirUsesXDGStateHome(t *testing.T) {
	t.Setenv("XDG_STATE_HOME", "/custom/state")
	got, err := defaultStateDir()
	if err != nil {
		t.Fatal(err)
	}
	if want := filepath.Join("/custom/state", "titan-ssh"); got != want {
		t.Fatalf("got %q, want %q", got, want)
	}
}

func TestDefaultStateDirFallsBackToLocalState(t *testing.T) {
	tests := []struct {
		name, xdg string
	}{
		{name: "unset", xdg: ""},
		{name: "relative is ignored", xdg: "relative/state"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			t.Setenv("HOME", "/home/someone")
			t.Setenv("XDG_STATE_HOME", tt.xdg)
			got, err := defaultStateDir()
			if err != nil {
				t.Fatal(err)
			}
			if want := "/home/someone/.local/state/titan-ssh"; got != want {
				t.Fatalf("got %q, want %q", got, want)
			}
		})
	}
}

func TestEnsureStateDirCreatesPrivateDir(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "a", "titan")
	if err := ensureStateDir(dir); err != nil {
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

func TestEnsureStateDirRejectsSharedDir(t *testing.T) {
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
			if err := ensureStateDir(dir); err == nil {
				t.Fatalf("dir with mode %v must be rejected", tt.mode)
			}
		})
	}
}

func TestEnsureStateDirRejectsSymlink(t *testing.T) {
	base := t.TempDir()
	target := filepath.Join(base, "real")
	if err := os.Mkdir(target, 0o700); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(base, "link")
	if err := os.Symlink(target, link); err != nil {
		t.Fatal(err)
	}
	if err := ensureStateDir(link); err == nil {
		t.Fatal("symlinked state dir must be rejected")
	}
}

func TestWriteStateIsOwnerOnly(t *testing.T) {
	dir := t.TempDir()
	if err := writeState(dir, validState()); err != nil {
		t.Fatal(err)
	}
	fi, err := os.Stat(filepath.Join(dir, stateFileName))
	if err != nil {
		t.Fatal(err)
	}
	if fi.Mode().Perm() != 0o600 {
		t.Fatalf("state file holds the token and must be 0600, got %v", fi.Mode().Perm())
	}
}
