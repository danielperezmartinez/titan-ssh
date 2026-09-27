package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

func TestDefaultStateDirUsesLocalAppData(t *testing.T) {
	t.Setenv("LOCALAPPDATA", `C:\Profile\AppData\Local`)
	got, err := defaultStateDir()
	if err != nil {
		t.Fatal(err)
	}
	if want := `C:\Profile\AppData\Local\titan-ssh`; got != want {
		t.Fatalf("got %q, want %q", got, want)
	}
}

func TestEnsureStateDirCreatesDir(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "a", "titan")
	if err := ensureStateDir(dir); err != nil {
		t.Fatalf("fresh dir should be accepted: %v", err)
	}
	if fi, err := os.Stat(dir); err != nil || !fi.IsDir() {
		t.Fatalf("dir should exist: %v", err)
	}
}

func TestEnsureStateDirRejectsFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "file")
	if err := os.WriteFile(path, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := ensureStateDir(path); err == nil {
		t.Fatal("a file in place of the state dir must be rejected")
	}
}

func TestEnsureStateDirRejectsJunction(t *testing.T) {
	base := t.TempDir()
	target := filepath.Join(base, "real")
	if err := os.Mkdir(target, 0o700); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(base, "link")
	// Junctions need no privilege, unlike symlinks.
	if out, err := exec.Command("cmd", "/c", "mklink", "/J", link, target).CombinedOutput(); err != nil {
		t.Skipf("cannot create a junction: %v: %s", err, out)
	}
	if err := ensureStateDir(link); err == nil {
		t.Fatal("a junction in place of the state dir must be rejected")
	}
}
