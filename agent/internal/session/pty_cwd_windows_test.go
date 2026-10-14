package session

import (
	"bytes"
	"io"
	"os"
	"os/exec"
	"strings"
	"sync"
	"testing"
	"time"
)

// PowerShell, started with the prompt hook, reports the folder the user is
// in after each prompt, nothing in another provider, and its report goes
// away with the session. The prompt itself still shows.
func TestPowerShellReportsItsFolder(t *testing.T) {
	for _, shell := range []string{"powershell.exe", "pwsh.exe"} {
		t.Run(shell, func(t *testing.T) {
			path, err := exec.LookPath(shell)
			if err != nil {
				t.Skip(shell, "is not installed")
			}
			p, err := startShell(path, 120, 30)
			if err != nil {
				t.Fatal(err)
			}
			if p.cwdFile == "" {
				t.Fatal("PowerShell started without the hook")
			}
			var mu sync.Mutex
			var out bytes.Buffer
			go func() {
				buf := make([]byte, 4096)
				for {
					n, err := p.Read(buf)
					mu.Lock()
					out.Write(buf[:n])
					mu.Unlock()
					if err != nil {
						return
					}
				}
			}()
			output := func() string { mu.Lock(); defer mu.Unlock(); return out.String() }
			waitUntil(t, "the first prompt", func() bool { return p.Cwd() != "" })
			if !strings.Contains(output(), "PS ") {
				t.Fatalf("no prompt in the output: %q", output())
			}

			dir := t.TempDir()
			if _, err := p.Write([]byte("Set-Location -LiteralPath '" + dir + "'" + enter)); err != nil {
				t.Fatal(err)
			}
			waitUntil(t, "the new folder", func() bool { return strings.EqualFold(p.Cwd(), dir) })

			if _, err := p.Write([]byte(`Set-Location HKCU:\` + enter)); err != nil {
				t.Fatal(err)
			}
			waitUntil(t, "no folder in the registry", func() bool { return p.Cwd() == "" })

			file := p.cwdFile
			if err := p.Close(); err != nil {
				t.Fatal(err)
			}
			if _, err := os.Stat(file); !os.IsNotExist(err) {
				t.Fatalf("the hook's file outlives the session: %v", err)
			}
		})
	}
}

// cmd.exe starts as it is: its folder is read from the process instead.
func TestCmdStartsWithoutTheHook(t *testing.T) {
	p, err := startShell("cmd.exe", 80, 24)
	if err != nil {
		t.Fatal(err)
	}
	go func() { _, _ = io.Copy(io.Discard, p) }()
	defer p.Close()
	if p.cwdFile != "" || p.Cwd() != "" || p.Shell() != "cmd.exe" {
		t.Fatalf("cmd.exe: file %q, cwd %q, shell %q", p.cwdFile, p.Cwd(), p.Shell())
	}
}

func waitUntil(t *testing.T, what string, ok func() bool) {
	t.Helper()
	deadline := time.Now().Add(30 * time.Second)
	for !ok() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(50 * time.Millisecond)
	}
}
