package procmem

import (
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"
)

// Cwd reads a process's directory from its memory: this test's own, and a
// cmd.exe started elsewhere.
func TestCwdReadsTheWorkingDirectory(t *testing.T) {
	want, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	if got := driveDir(Cwd(os.Getpid())); !strings.EqualFold(got, driveDir(want)) {
		t.Fatalf("own Cwd = %q; want %q", got, want)
	}
	dir := t.TempDir()
	cmd := exec.Command("cmd.exe", "/c", "ping -n 30 127.0.0.1 >nul")
	cmd.Dir = dir
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = cmd.Process.Kill(); _ = cmd.Wait() })
	deadline := time.Now().Add(5 * time.Second)
	for {
		got := driveDir(Cwd(cmd.Process.Pid))
		if strings.EqualFold(got, driveDir(dir)) {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("cmd's Cwd = %q; want %q", got, dir)
		}
		time.Sleep(20 * time.Millisecond)
	}
	if got := Cwd(-1); got != "" {
		t.Fatalf("Cwd of a missing pid = %q", got)
	}
}
