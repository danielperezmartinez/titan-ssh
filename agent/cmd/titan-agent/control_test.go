package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"io/fs"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func waitStatus(t *testing.T, dir, what string, cond func(statusReport) bool) statusReport {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for {
		st, err := queryStatus(dir)
		if err != nil {
			t.Fatal(err)
		}
		if cond(st) {
			return st
		}
		if time.Now().After(deadline) {
			t.Fatalf("status never showed %s: %+v", what, st)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func TestStatusReportsTheDaemonAndItsSessions(t *testing.T) {
	shortTimeouts(t)
	dir := testStateDir(t)
	startTestDaemon(t, dir)
	st, _ := readState(dir)
	conn, err := dialDaemon(st)
	if err != nil {
		t.Fatal(err)
	}
	helloConn(t, conn, "s1")

	rep := waitStatus(t, dir, "an attached session", func(r statusReport) bool {
		return len(r.Sessions) == 1 && r.Sessions[0].Clients == 1
	})
	if rep.State != stateRunning || rep.Agent != version || rep.PID != os.Getpid() || rep.Schema != statusSchema {
		t.Fatalf("daemon fields: %+v", rep)
	}
	if rep.NowMs == 0 || rep.StartedMs == 0 || rep.StartedMs > rep.NowMs {
		t.Fatalf("times: now=%d started=%d", rep.NowMs, rep.StartedMs)
	}
	s := rep.Sessions[0]
	if s.ID != "s1" || s.DetachedMs != 0 || s.CreatedMs == 0 {
		t.Fatalf("attached session: %+v", s)
	}

	conn.Close()
	waitStatus(t, dir, "the session detached", func(r statusReport) bool {
		return len(r.Sessions) == 1 && r.Sessions[0].Clients == 0 && r.Sessions[0].DetachedMs > 0
	})
}

func TestCloseSessionEndsItAndIsIdempotent(t *testing.T) {
	shortTimeouts(t)
	dir := testStateDir(t)
	startTestDaemon(t, dir)
	st, _ := readState(dir)
	conn, err := dialDaemon(st)
	if err != nil {
		t.Fatal(err)
	}
	helloConn(t, conn, "s1")
	conn.Close()

	if closed, err := closeSession(dir, "s1"); err != nil || !closed {
		t.Fatalf("closeSession = %v, %v", closed, err)
	}
	if rep, _ := queryStatus(dir); len(rep.Sessions) != 0 {
		t.Fatalf("the session is still listed: %+v", rep.Sessions)
	}
	if closed, err := closeSession(dir, "s1"); err != nil || closed {
		t.Fatalf("a second close = %v, %v; want false, nil", closed, err)
	}
}

// With no daemon at all there is nothing to close, and that is not an error.
func TestCloseSessionWithoutDaemon(t *testing.T) {
	dir := testStateDir(t)
	if closed, err := closeSession(dir, "s1"); err != nil || closed {
		t.Fatalf("closeSession = %v, %v", closed, err)
	}
}

// A stop request makes serve return and the daemon close every session.
func TestStopRequestEndsTheDaemonInOrder(t *testing.T) {
	shortTimeouts(t)
	dir := testStateDir(t)
	d, err := startDaemon(dir, 1<<16, newIdlePty)
	if err != nil {
		t.Fatal(err)
	}
	served := make(chan error, 1)
	go func() { served <- d.serve() }()
	st, _ := readState(dir)
	conn, err := dialDaemon(st)
	if err != nil {
		t.Fatal(err)
	}
	helloConn(t, conn, "s1")
	defer conn.Close()

	if _, _, err := request(dir, controlRequest{Op: opStop}); err != nil {
		t.Fatal(err)
	}
	select {
	case <-served:
	case <-time.After(2 * time.Second):
		t.Fatal("serve did not return after a stop request")
	}
	if !d.stopping.Load() {
		t.Fatal("the daemon should know it is stopping")
	}
	if n := d.reg.CloseAll(); n != 1 {
		t.Fatalf("the session should still be there to close, got %d", n)
	}
	d.close()
	if _, err := os.Stat(filepath.Join(dir, stateFileName)); !errors.Is(err, fs.ErrNotExist) {
		t.Fatalf("a stopped daemon removes its state file, got %v", err)
	}
}

func TestStatusWithoutDaemonIsStopped(t *testing.T) {
	dir := testStateDir(t)
	rep, err := queryStatus(dir)
	if err != nil || rep.State != stateStopped || rep.CLI != version {
		t.Fatalf("status = %+v, %v", rep, err)
	}
}

// A lock holder without a state file cannot be reached.
func TestStatusWithLockButNoStateIsUnreachable(t *testing.T) {
	dir := testStateDir(t)
	lock, err := lockDaemon(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer lock.Close()
	rep, err := queryStatus(dir)
	if err != nil || rep.State != stateUnreachable {
		t.Fatalf("status = %+v, %v", rep, err)
	}
}

// fakeLegacyDaemon accepts only the session preamble, as agents before the
// control connection did, and publishes itself as version v.
func fakeLegacyDaemon(t *testing.T, dir, v string) {
	t.Helper()
	lock, err := lockDaemon(dir)
	if err != nil {
		t.Fatal(err)
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	token := bytes.Repeat([]byte{7}, tokenLen)
	st := validState()
	st.Agent, st.Port, st.PID = v, ln.Addr().(*net.TCPAddr).Port, 424242
	st.Token = strings.Repeat("07", tokenLen)
	if err := writeState(dir, st); err != nil {
		t.Fatal(err)
	}
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			pre := make([]byte, len(preambleMagic)+tokenLen)
			if _, err := io.ReadFull(conn, pre); err == nil &&
				string(pre[:len(preambleMagic)]) == preambleMagic && bytes.Equal(pre[len(preambleMagic):], token) {
				_, _ = conn.Write([]byte(preambleAck))
			}
			conn.Close()
		}
	}()
	t.Cleanup(func() { ln.Close(); lock.Close() })
}

func TestStatusOfAnOlderDaemonIsLegacy(t *testing.T) {
	shortTimeouts(t)
	dir := testStateDir(t)
	fakeLegacyDaemon(t, dir, "0.1.0-beta.3")
	rep, err := queryStatus(dir)
	if err != nil || rep.State != stateLegacy || rep.Agent != "0.1.0-beta.3" || rep.PID != 424242 {
		t.Fatalf("status = %+v, %v", rep, err)
	}
	if _, err := closeSession(dir, "s1"); !errors.Is(err, errLegacy) {
		t.Fatalf("closing on an older daemon should say so, got %v", err)
	}
}

// The daemon puts its state file back when it goes missing, so it never ends
// up holding the lock where no front can reach it.
func TestDaemonRepublishesAMissingStateFile(t *testing.T) {
	shortTimeouts(t)
	old := maintainInterval
	maintainInterval = 20 * time.Millisecond
	t.Cleanup(func() { maintainInterval = old })
	dir := testStateDir(t)
	d := startTestDaemon(t, dir)
	quit := make(chan struct{})
	done := make(chan struct{})
	go func() { d.maintain(quit); close(done) }()
	t.Cleanup(func() { close(quit); <-done })

	if err := os.Remove(filepath.Join(dir, stateFileName)); err != nil {
		t.Fatal(err)
	}
	waitFor(t, "the state file to come back", func() bool {
		st, err := readState(dir)
		return err == nil && st == d.record
	})
	if _, err := queryStatus(dir); err != nil {
		t.Fatal(err)
	}
}

func TestStatusJSONContract(t *testing.T) {
	mem := uint64(2048)
	rep := statusReport{
		Schema: 1, State: stateRunning, Agent: "1.0.0", CLI: "1.0.0", PID: 9, OS: "linux", Arch: "amd64",
		NowMs: 3000, StartedMs: 1000, MemoryBytes: &mem,
		Sessions: []sessionReport{{ID: "s1", CreatedMs: 1000, LastUsedMs: 2000, DetachedMs: 2000, BufferBytes: 5}},
	}
	data, err := json.Marshal(rep)
	if err != nil {
		t.Fatal(err)
	}
	want := `{"schema":1,"state":"running","agent":"1.0.0","cli":"1.0.0","pid":9,"os":"linux","arch":"amd64",` +
		`"nowMs":3000,"startedMs":1000,"memoryBytes":2048,"sessions":[{"id":"s1","createdMs":1000,` +
		`"lastUsedMs":2000,"detachedMs":2000,"clients":0,"bufferBytes":5}]}`
	if string(data) != want {
		t.Fatalf("JSON contract changed:\n got %s\nwant %s", data, want)
	}

	// The session details added later are all optional fields.
	cpu := 12
	full := sessionReport{
		ID: "s2", CreatedMs: 1, LastUsedMs: 2, BufferBytes: 3, CPUPercent: &cpu,
		Shell: "/bin/bash", ShellPID: 40, Foreground: "vim", ForegroundPID: 41, Cwd: "/tmp",
		Cols: 80, Rows: 24, LastOutputMs: 2, Title: "vim notes",
	}
	data, err = json.Marshal(full)
	if err != nil {
		t.Fatal(err)
	}
	want = `{"id":"s2","createdMs":1,"lastUsedMs":2,"clients":0,"bufferBytes":3,"cpuPercent":12,` +
		`"shell":"/bin/bash","shellPid":40,"foreground":"vim","foregroundPid":41,"cwd":"/tmp",` +
		`"cols":80,"rows":24,"lastOutputMs":2,"title":"vim notes"}`
	if string(data) != want {
		t.Fatalf("session JSON contract changed:\n got %s\nwant %s", data, want)
	}

	data, err = json.Marshal(previewReport{Cols: 80, Rows: 24, Data: []byte("hi")})
	if err != nil {
		t.Fatal(err)
	}
	if want := `{"cols":80,"rows":24,"data":"aGk="}`; string(data) != want {
		t.Fatalf("preview JSON contract changed:\n got %s\nwant %s", data, want)
	}
}

// The status reports the session's size, and --preview gives the end of its
// history; a session the daemon does not hold has no preview.
func TestPreviewGivesTheEndOfTheHistory(t *testing.T) {
	shortTimeouts(t)
	dir := testStateDir(t)
	d := startTestDaemon(t, dir)
	st, _ := readState(dir)
	conn, err := dialDaemon(st)
	if err != nil {
		t.Fatal(err)
	}
	helloConn(t, conn, "s1")
	defer conn.Close()

	rep := waitStatus(t, dir, "the session", func(r statusReport) bool { return len(r.Sessions) == 1 })
	if s := rep.Sessions[0]; s.Cols == 0 || s.Rows == 0 {
		t.Fatalf("session size missing: %+v", s)
	}
	if _, _, _, ok := d.reg.Tail("s1", 1); !ok {
		t.Fatal("the registry must hold s1")
	}
	p, err := queryPreview(dir, "s1")
	if err != nil {
		t.Fatal(err)
	}
	if p.Cols != rep.Sessions[0].Cols || p.Rows != rep.Sessions[0].Rows {
		t.Fatalf("preview size %dx%d; status says %dx%d", p.Cols, p.Rows, rep.Sessions[0].Cols, rep.Sessions[0].Rows)
	}
	if _, err := queryPreview(dir, "missing"); !errors.Is(err, errNoSession) {
		t.Fatalf("preview of a missing session: %v", err)
	}
}

func TestPreviewWithoutDaemonHasNoSession(t *testing.T) {
	dir := testStateDir(t)
	if _, err := queryPreview(dir, "s1"); !errors.Is(err, errNoSession) {
		t.Fatalf("preview with no daemon: %v", err)
	}
}
