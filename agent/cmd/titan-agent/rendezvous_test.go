package main

import (
	"bytes"
	"errors"
	"io"
	"net"
	"os"
	"path/filepath"
	"sync"
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
	oldShake, oldSample := handshakeTimeout, cpuSample
	spawnWait, pollInterval, respawnGap = 2*time.Second, 10*time.Millisecond, 200*time.Millisecond
	// Room for the handshake's round trips on a loaded machine (-race).
	helloTimeout, handshakeTimeout, dialTimeout = 500*time.Millisecond, 500*time.Millisecond, time.Second
	cpuSample = 10 * time.Millisecond
	t.Cleanup(func() {
		spawnWait, pollInterval, respawnGap = oldWait, oldPoll, oldGap
		helloTimeout, handshakeTimeout, dialTimeout = oldHello, oldShake, oldDial
		cpuSample = oldSample
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
		{name: "wrong token", preamble: append([]byte(legacyPreambleMagic), bad...)},
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

func TestDaemonBoundsConnectionsInTheirHandshake(t *testing.T) {
	shortTimeouts(t)
	handshakeTimeout = 10 * time.Second // the silent ones must outlast the test
	dir := testStateDir(t)
	d := startTestDaemon(t, dir)
	st, err := readState(dir)
	if err != nil {
		t.Fatal(err)
	}
	live, err := dialDaemon(st)
	if err != nil {
		t.Fatal(err)
	}
	defer live.Close()
	helloConn(t, live, "s1")

	waitSlots := func(n int) {
		t.Helper()
		deadline := time.Now().Add(5 * time.Second)
		for len(d.slots) != n {
			if time.Now().After(deadline) {
				t.Fatalf("%d connections in their handshake, want %d", len(d.slots), n)
			}
			time.Sleep(5 * time.Millisecond)
		}
	}
	var silent []net.Conn
	defer func() {
		for _, c := range silent {
			c.Close()
		}
	}()
	for range maxHandshakes {
		c, err := net.Dial("tcp", d.ln.Addr().String())
		if err != nil {
			t.Fatal(err)
		}
		silent = append(silent, c)
	}
	waitSlots(maxHandshakes)

	extra, err := net.Dial("tcp", d.ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer extra.Close()
	_ = extra.SetReadDeadline(time.Now().Add(2 * time.Second))
	_, err = io.ReadAll(extra)
	var ne net.Error
	if errors.As(err, &ne) && ne.Timeout() {
		t.Fatal("a connection past the limit must be closed at once")
	}

	for _, c := range silent {
		c.Close()
	}
	silent = nil
	waitSlots(0)
	conn, err := dialDaemon(st)
	if err != nil {
		t.Fatalf("the daemon must accept again once the slots free up: %v", err)
	}
	defer conn.Close()
	if f := helloConn(t, conn, "s1"); f.Created {
		t.Fatal("the session opened before the flood must have survived it")
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
	st.Agent, st.Port = version, ln.Addr().(*net.TCPAddr).Port
	if err := writeState(dir, st); err != nil {
		t.Fatal(err)
	}
	_, err = dialOrSpawn(dir, func() error { t.Error("must not launch while a daemon holds the lock"); return nil })
	assertCode(t, err, codeAuth)
}

func TestDialOrSpawnReportsAnOlderDaemon(t *testing.T) {
	shortTimeouts(t)
	spawnWait = 500 * time.Millisecond
	dir := testStateDir(t)
	older := fakeOlderDaemon(t, dir, "0.1.0-beta.10", true)
	_, err := dialOrSpawn(dir, func() error { t.Error("must not launch while a daemon holds the lock"); return nil })
	assertCode(t, err, codeAgentOutdated)
	if older.tokenSeen() {
		t.Fatal("a session must not be opened with the older handshake")
	}
}

// A listener that is not the daemon gets the opening bytes of the handshake
// at most, whatever it answers, and the front still ends up on a daemon.
func TestDialOrSpawnOnlyTalksToItsDaemon(t *testing.T) {
	answers := map[string]func(net.Conn, []byte){
		"closes":                 func(net.Conn, []byte) {},
		"acks like an older one": func(c net.Conn, _ []byte) { _, _ = c.Write([]byte(legacyPreambleAck)) },
		"sends a made-up proof":  func(c net.Conn, _ []byte) { _, _ = c.Write(bytes.Repeat([]byte{1}, nonceLen+proofLen)) },
		"echoes the nonce back": func(c net.Conn, pre []byte) {
			_, _ = c.Write(append(append([]byte{}, pre[magicLen:]...), pre[magicLen:]...))
		},
	}
	for name, answer := range answers {
		t.Run(name, func(t *testing.T) {
			shortTimeouts(t)
			dir := testStateDir(t)
			other := newRecordingListener(t, answer)
			stale := validState()
			stale.Port = other.port()
			if err := writeState(dir, stale); err != nil {
				t.Fatal(err)
			}

			conn, err := dialOrSpawn(dir, func() error { startTestDaemon(t, dir); return nil })
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			helloConn(t, conn, "s1")
			if _, err := conn.Write([]byte("typed after the attach")); err != nil {
				t.Fatal(err)
			}
			for _, got := range other.received() {
				if len(got) > magicLen+nonceLen {
					t.Fatalf("the listener got %d bytes on one connection: %q", len(got), got)
				}
				if bytes.Contains(got, mustToken(t, stale)) {
					t.Fatal("the listener got the token")
				}
			}
		})
	}
}

func TestDaemonClosesAConnectionWithAWrongProof(t *testing.T) {
	shortTimeouts(t)
	dir := testStateDir(t)
	d := startTestDaemon(t, dir)
	conn, err := net.Dial("tcp", d.ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := conn.Write(append([]byte(preambleMagic), make([]byte, nonceLen)...)); err != nil {
		t.Fatal(err)
	}
	if _, err := io.ReadFull(conn, make([]byte, nonceLen+proofLen)); err != nil {
		t.Fatal(err)
	}
	if _, err := conn.Write(make([]byte, proofLen)); err != nil {
		t.Fatal(err)
	}
	_, _ = conn.Write(protocol.Encode(protocol.Frame{Type: protocol.TypeHello, SessionID: "x", Cols: 80, Rows: 24}))
	got, err := io.ReadAll(conn)
	var ne net.Error
	if errors.As(err, &ne) && ne.Timeout() {
		t.Fatal("the daemon must close the connection, not leave it open")
	}
	if len(got) != 0 {
		t.Fatalf("the daemon must not serve a peer without the token, got %q", got)
	}
}

func TestBothEndsCheckTheHandshake(t *testing.T) {
	token := bytes.Repeat([]byte{3}, tokenLen)
	other := bytes.Repeat([]byte{4}, tokenLen)
	nf, nd := bytes.Repeat([]byte{5}, nonceLen), bytes.Repeat([]byte{6}, nonceLen)
	base := handshakeProof(token, roleServer, preambleMagic, nf, nd)
	for name, p := range map[string][]byte{
		"role":   handshakeProof(token, roleClient, preambleMagic, nf, nd),
		"token":  handshakeProof(other, roleServer, preambleMagic, nf, nd),
		"magic":  handshakeProof(token, roleServer, controlMagic, nf, nd),
		"nonces": handshakeProof(token, roleServer, preambleMagic, nd, nf),
	} {
		if bytes.Equal(p, base) {
			t.Errorf("a proof with another %s must differ", name)
		}
	}
	if len(base) != proofLen || len(preambleMagic) != magicLen || len(legacyPreambleMagic) != magicLen || nonceLen != tokenLen {
		t.Fatal("both handshakes must open with the same number of bytes")
	}
}

// recordingListener accepts connections on loopback, reads the opening bytes
// of each, answers with its func and records everything each one sent.
type recordingListener struct {
	ln   net.Listener
	mu   sync.Mutex
	got  [][]byte
	done sync.WaitGroup
}

func newRecordingListener(t *testing.T, answer func(net.Conn, []byte)) *recordingListener {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	r := &recordingListener{ln: ln}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			r.done.Add(1)
			go func() {
				defer r.done.Done()
				defer c.Close()
				_ = c.SetDeadline(time.Now().Add(2 * time.Second))
				pre := make([]byte, magicLen+nonceLen)
				n, _ := io.ReadFull(c, pre)
				if n == len(pre) {
					answer(c, pre)
				}
				rest, _ := io.ReadAll(c)
				r.mu.Lock()
				r.got = append(r.got, append(pre[:n], rest...))
				r.mu.Unlock()
			}()
		}
	}()
	t.Cleanup(func() { ln.Close(); r.done.Wait() })
	return r
}

func (r *recordingListener) port() int { return r.ln.Addr().(*net.TCPAddr).Port }

// received waits for the open connections to end and returns what each sent.
func (r *recordingListener) received() [][]byte {
	r.ln.Close()
	r.done.Wait()
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.got
}

func mustToken(t *testing.T, st agentState) []byte {
	t.Helper()
	token, err := st.token()
	if err != nil {
		t.Fatal(err)
	}
	return token
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
