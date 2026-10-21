//go:build linux

package dbus

import (
	"bufio"
	"context"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"

	"golang.org/x/sys/unix"
)

// privateBus starts a throwaway dbus-daemon for the test and returns its
// address; the test is skipped where there is none.
func privateBus(t *testing.T) string {
	t.Helper()
	bin, err := exec.LookPath("dbus-daemon")
	if err != nil {
		t.Skip("no dbus-daemon")
	}
	cmd := exec.Command(bin, "--session", "--nofork", "--nopidfile", "--print-address=1", "--address=unix:path="+t.TempDir()+"/bus")
	out, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := cmd.Start(); err != nil {
		t.Skip("dbus-daemon does not start:", err)
	}
	t.Cleanup(func() { _ = cmd.Process.Kill(); _ = cmd.Wait() })
	line, err := bufio.NewReader(out).ReadString('\n')
	if err != nil {
		t.Fatal(err)
	}
	return strings.TrimSpace(line)
}

func TestLiveBus(t *testing.T) {
	addr := privateBus(t)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	a, err := Dial(addr)
	if err != nil {
		t.Fatal(err)
	}
	defer a.Close()
	if !strings.HasPrefix(a.UniqueName(), ":") {
		t.Fatalf("unique name %q", a.UniqueName())
	}

	reply, err := a.Call(ctx, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "ListNames", "")
	if err != nil {
		t.Fatal(err)
	}
	if names, _ := reply.Body[0].([]any); len(names) < 2 {
		t.Fatalf("names = %v", reply.Body)
	}

	// An error reply.
	_, err = a.Call(ctx, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "NoSuchMethod", "")
	if e, ok := err.(*Error); !ok || !strings.Contains(e.Name, "UnknownMethod") {
		t.Fatalf("err = %v", err)
	}

	// A signal from another connection, and a descriptor with it.
	b, err := Dial(addr)
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	ch, stop, err := a.Watch(ctx, "", "/test", "io.test", "Ping")
	if err != nil {
		t.Fatal(err)
	}
	defer stop()
	f, err := os.CreateTemp(t.TempDir(), "fd")
	if err != nil {
		t.Fatal(err)
	}
	_, _ = f.WriteString("through the bus")
	sig := &Message{Type: TypeSignal, Path: "/test", Interface: "io.test", Member: "Ping", Signature: "sh", Body: []any{"hi", FD(f.Fd())}}
	if _, _, err := b.send(sig); err != nil {
		t.Fatal(err)
	}
	select {
	case m := <-ch:
		if m.Body[0] != "hi" || len(m.Files) != 1 {
			t.Fatalf("signal = %+v", m)
		}
		buf := make([]byte, 64)
		n, err := unix.Pread(m.Files[m.Body[1].(FDIndex)], buf, 0)
		if err != nil || string(buf[:n]) != "through the bus" {
			t.Fatalf("fd read %q %v", buf[:n], err)
		}
		closeFiles(m.Files)
	case <-ctx.Done():
		t.Fatal("no signal")
	}

	a.Close()
	if _, err := a.Call(ctx, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "ListNames", ""); err == nil {
		t.Fatal("call on a closed connection")
	}
}
