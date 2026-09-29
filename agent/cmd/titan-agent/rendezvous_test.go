package main

import (
	"errors"
	"io"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/protocol"
)

// frameReader decodes protocol frames from a byte stream.
type frameReader struct {
	r       io.Reader
	dec     protocol.Decoder
	pending []protocol.Frame
}

func (fr *frameReader) next() (protocol.Frame, error) {
	buf := make([]byte, 32*1024)
	for len(fr.pending) == 0 {
		n, err := fr.r.Read(buf)
		if n > 0 {
			frames, derr := fr.dec.Feed(buf[:n])
			if derr != nil {
				return protocol.Frame{}, derr
			}
			fr.pending = append(fr.pending, frames...)
		}
		if err != nil && len(fr.pending) == 0 {
			return protocol.Frame{}, err
		}
	}
	f := fr.pending[0]
	fr.pending = fr.pending[1:]
	return f, nil
}

// hello sends HELLO for id on w and returns the HELLO_OK read from fr.
func hello(t *testing.T, w io.Writer, fr *frameReader, id string) protocol.Frame {
	t.Helper()
	if _, err := w.Write(protocol.Encode(protocol.Frame{Type: protocol.TypeHello, SessionID: id, Cols: 80, Rows: 24})); err != nil {
		t.Fatal(err)
	}
	for {
		f, err := fr.next()
		if err != nil {
			t.Fatalf("no HELLO_OK: %v", err)
		}
		if f.Type == protocol.TypeHelloOK {
			return f
		}
	}
}

// helloConn runs HELLO over an authenticated daemon connection.
func helloConn(t *testing.T, conn net.Conn, id string) protocol.Frame {
	t.Helper()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	defer conn.SetDeadline(time.Time{})
	return hello(t, conn, &frameReader{r: conn}, id)
}

// startTestDaemon runs an in-process daemon on dir with idle fake PTYs.
func startTestDaemon(t *testing.T, dir string) *daemon {
	t.Helper()
	d, err := startDaemon(dir, 1<<16, newIdlePty)
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	go func() { _ = d.serve(); close(done) }()
	t.Cleanup(func() {
		d.ln.Close()
		<-done         // serve returned: no more connection goroutines start
		d.conns.Wait() // nor outlive the test (they read its shortened timeouts)
		d.close()
		d.reg.CloseAll()
	})
	return d
}

func shortTimeouts(t *testing.T) {
	t.Helper()
	oldWait, oldPoll, oldGap, oldHello, oldDial := spawnWait, pollInterval, respawnGap, helloTimeout, dialTimeout
	spawnWait, pollInterval, respawnGap = 2*time.Second, 10*time.Millisecond, 200*time.Millisecond
	helloTimeout, dialTimeout = 200*time.Millisecond, time.Second
	t.Cleanup(func() {
		spawnWait, pollInterval, respawnGap = oldWait, oldPoll, oldGap
		helloTimeout, dialTimeout = oldHello, oldDial
	})
}

func TestDaemonPublishesItsLoopbackListener(t *testing.T) {
	dir := testStateDir(t)
	d := startTestDaemon(t, dir)
	st, err := readState(dir)
	if err != nil {
		t.Fatal(err)
	}
	addr := d.ln.Addr().(*net.TCPAddr)
	if !addr.IP.Equal(net.IPv4(127, 0, 0, 1)) {
		t.Fatalf("daemon must listen on 127.0.0.1 only, got %v", addr)
	}
	if st.Port != addr.Port || st.PID != os.Getpid() || st.Agent != version {
		t.Fatalf("state %+v does not describe this daemon (%v)", st, addr)
	}
	conn, err := dialDaemon(st)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if f := helloConn(t, conn, "s1"); !f.Created {
		t.Fatal("first attach should create the session")
	}
}

func TestSecondDaemonBacksOffAndFirstKeepsServing(t *testing.T) {
	dir := testStateDir(t)
	startTestDaemon(t, dir)
	before, err := readState(dir)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := startDaemon(dir, 1<<16, newIdlePty); !errors.Is(err, errLocked) {
		t.Fatalf("a second daemon must see the lock taken, got %v", err)
	}
	if err := runDaemon(dir, 1<<16); err != nil {
		t.Fatalf("--daemon must exit cleanly when a daemon already runs: %v", err)
	}
	after, err := readState(dir)
	if err != nil || after != before {
		t.Fatalf("the losing daemon must not touch the state file: %+v, %v", after, err)
	}
	conn, err := dialDaemon(after)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	helloConn(t, conn, "s1")
}

func TestDaemonClosesUnauthenticatedConnections(t *testing.T) {
	shortTimeouts(t)
	dir := testStateDir(t)
	d := startTestDaemon(t, dir)
	good := d.token
	bad := make([]byte, tokenLen)
	tests := []struct {
		name     string
		preamble []byte
	}{
		{name: "wrong token", preamble: append([]byte(preambleMagic), bad...)},
		{name: "wrong magic", preamble: append([]byte("NOTTITAN"), good...)},
		{name: "silent", preamble: nil},
		{name: "a HELLO frame instead", preamble: protocol.Encode(protocol.Frame{Type: protocol.TypeHello, SessionID: "x", Cols: 80, Rows: 24})},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			conn, err := net.Dial("tcp", d.ln.Addr().String())
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			if len(tt.preamble) > 0 {
				if _, err := conn.Write(tt.preamble); err != nil {
					t.Fatal(err)
				}
			}
			_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
			got, err := io.ReadAll(conn)
			var ne net.Error
			if errors.As(err, &ne) && ne.Timeout() {
				t.Fatal("the daemon must close the connection, not leave it open")
			}
			if len(got) != 0 {
				t.Fatalf("the daemon must not answer an unauthenticated peer, got %q", got)
			}
		})
	}
}

func TestDialOrSpawnReplacesStaleState(t *testing.T) {
	shortTimeouts(t)
	dir := testStateDir(t)
	// A state file left by a dead daemon: nothing listens on its port.
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	stale := validState()
	stale.Port = ln.Addr().(*net.TCPAddr).Port
	ln.Close()
	if err := writeState(dir, stale); err != nil {
		t.Fatal(err)
	}

	spawns := 0
	conn, err := dialOrSpawn(dir, func() error { spawns++; startTestDaemon(t, dir); return nil })
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if spawns != 1 {
		t.Fatalf("want exactly one daemon launched, got %d", spawns)
	}
	helloConn(t, conn, "s1")
}

func TestDialOrSpawnReusesLiveDaemon(t *testing.T) {
	shortTimeouts(t)
	dir := testStateDir(t)
	startTestDaemon(t, dir)
	conn, err := dialOrSpawn(dir, func() error { t.Error("must not launch a daemon when one answers"); return nil })
	if err != nil {
		t.Fatal(err)
	}
	conn.Close()
}

func TestDialOrSpawnGivesUpWithDaemonStart(t *testing.T) {
	shortTimeouts(t)
	spawnWait = 300 * time.Millisecond
	_, err := dialOrSpawn(testStateDir(t), func() error { return nil }) // the daemon never comes up
	assertCode(t, err, codeDaemonStart)
}

func TestDialOrSpawnReportsRejectedToken(t *testing.T) {
	shortTimeouts(t)
	spawnWait = 500 * time.Millisecond
	dir := testStateDir(t)
	// A live daemon (it holds the lock) that does not accept our token.
	lock, err := lockDaemon(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer lock.Close()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			c.Close()
		}
	}()
	st := validState()
	st.Port = ln.Addr().(*net.TCPAddr).Port
	if err := writeState(dir, st); err != nil {
		t.Fatal(err)
	}
	_, err = dialOrSpawn(dir, func() error { t.Error("must not launch while a daemon holds the lock"); return nil })
	assertCode(t, err, codeAuth)
}

func TestRunFrontRejectsUnusableStateDir(t *testing.T) {
	file := filepath.Join(testStateDir(t), "file")
	if err := os.WriteFile(file, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	assertCode(t, runFront(file), codeStateDir)
}

func assertCode(t *testing.T, err error, code string) {
	t.Helper()
	var ae *agentError
	if !errors.As(err, &ae) || ae.Code != code {
		t.Fatalf("want a %s error, got %v", code, err)
	}
}
