package main

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"golang.org/x/sys/windows"
)

func TestProgramFlags(t *testing.T) {
	base := uint32(windows.CREATE_NEW_CONSOLE | windows.CREATE_DEFAULT_ERROR_MODE)
	cases := []struct {
		name   string
		inJob  bool
		limits uint32
		want   uint32
	}{
		{"no job", false, 0, base},
		{"job that lets children leave", true, windows.JOB_OBJECT_LIMIT_BREAKAWAY_OK, base | windows.CREATE_BREAKAWAY_FROM_JOB},
		{"job that keeps them", true, windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE, base},
	}
	for _, c := range cases {
		if got := programFlags(c.inJob, c.limits); got != c.want {
			t.Errorf("%s: flags = %#x, want %#x", c.name, got, c.want)
		}
	}
}

// startProgram starts a real program with its arguments and working
// directory as given, and returns its PID.
func TestStartProgramRunsTheProgram(t *testing.T) {
	cmd := filepath.Join(os.Getenv("SystemRoot"), "System32", "cmd.exe")
	dir := t.TempDir()
	pid, err := startProgram(runRequest{Program: cmd, Args: []string{"/c", "echo two words> out.txt"}, Dir: dir})
	if err != nil {
		t.Fatal(err)
	}
	if pid <= 0 {
		t.Fatalf("pid = %d", pid)
	}
	out := filepath.Join(dir, "out.txt")
	deadline := time.Now().Add(5 * time.Second)
	for {
		if data, err := os.ReadFile(out); err == nil && string(data) == "two words\r\n" {
			return
		}
		if time.Now().After(deadline) {
			data, err := os.ReadFile(out)
			t.Fatalf("out.txt = %q, %v; want the echo in the working directory", data, err)
		}
		time.Sleep(20 * time.Millisecond)
	}
}
