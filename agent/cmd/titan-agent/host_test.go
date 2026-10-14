package main

import (
	"errors"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/protocol"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/session"
)

// inProcessHosts starts each session host as a goroutine of the test, on
// newPty, instead of as a process: the daemon cannot tell the difference.
func inProcessHosts(dir string, newPty session.PtyFactory) hostSpawner {
	return func(id string, cols, rows uint16) (agentState, error) {
		ch := make(chan hostAnnounce, 1)
		go func() {
			_ = runHost(hostConfig{dir: dir, id: id, cols: cols, rows: rows, bufCap: 1 << 16}, newPty,
				func(a hostAnnounce) { ch <- a })
		}()
		return parseAnnounceValue(<-ch)
	}
}

func parseAnnounceValue(a hostAnnounce) (agentState, error) {
	if a.Error != "" {
		return agentState{}, reasonError(a.Error)
	}
	return *a.State, nil
}

// echoPty echoes its input as output, so a test can tell a session's history
// survives.
type echoPty struct {
	mu     sync.Mutex
	cond   *sync.Cond
	buf    []byte
	closed bool
}

func newEchoPty(cols, rows uint16) (session.Pty, error) {
	p := &echoPty{}
	p.cond = sync.NewCond(&p.mu)
	return p, nil
}

func (p *echoPty) Read(b []byte) (int, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	for len(p.buf) == 0 && !p.closed {
		p.cond.Wait()
	}
	if len(p.buf) == 0 {
		return 0, io.EOF
	}
	n := copy(b, p.buf)
	p.buf = p.buf[n:]
	return n, nil
}

func (p *echoPty) Write(b []byte) (int, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.closed {
		return 0, io.ErrClosedPipe
	}
	p.buf = append(p.buf, b...)
	p.cond.Broadcast()
	return len(b), nil
}

func (p *echoPty) Resize(c, r uint16) error { return nil }

func (p *echoPty) Close() error {
	p.mu.Lock()
	p.closed = true
	p.cond.Broadcast()
	p.mu.Unlock()
	return nil
}

// exitPty is a shell that has already exited.
func exitPty(cols, rows uint16) (session.Pty, error) {
	p := &echoPty{closed: true}
	p.cond = sync.NewCond(&p.mu)
	return p, nil
}

// startDaemonWith runs an in-process daemon on dir whose hosts use newPty,
// and returns a stop that ends it the way --restart does: the hosts stay.
func startDaemonWith(t *testing.T, dir string, newPty session.PtyFactory) (*daemon, func()) {
	t.Helper()
	d, err := startDaemon(dir, inProcessHosts(dir, newPty))
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	go func() { _ = d.serve(); close(done) }()
	var once sync.Once
	stop := func() {
		once.Do(func() {
			d.ln.Close()
			<-done
			d.close()
		})
	}
	t.Cleanup(func() {
		stop()
		d.conns.Wait()
	})
	return d, stop
}

// attachTo connects to the daemon published in dir and runs HELLO for id. The
// reader goes on with the frames after HELLO_OK.
func attachTo(t *testing.T, dir, id string) (net.Conn, *frameReader, protocol.Frame) {
	t.Helper()
	st, err := readState(dir)
	if err != nil {
		t.Fatal(err)
	}
	conn, err := dialDaemon(st)
	if err != nil {
		t.Fatal(err)
	}
	fr := &frameReader{r: conn}
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	defer conn.SetDeadline(time.Time{})
	return conn, fr, hello(t, conn, fr, id)
}

// readUntil reads DATA frames from conn until their bytes contain want.
func readUntil(t *testing.T, conn net.Conn, r *frameReader, want string) {
	t.Helper()
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	defer conn.SetReadDeadline(time.Time{})
	var got []byte
	for !strings.Contains(string(got), want) {
		f, err := r.next()
		if err != nil {
			t.Fatalf("waiting for %q, got %q: %v", want, got, err)
		}
		if f.Type == protocol.TypeData {
			got = append(got, f.Bytes...)
		}
	}
}

func send(t *testing.T, conn net.Conn, f protocol.Frame) {
	t.Helper()
	if _, err := conn.Write(protocol.Encode(f)); err != nil {
		t.Fatal(err)
	}
}

// The heart of ADR-0022: a session outlives the daemon that started it. A new
// daemon finds its host, reattaches without creating it again and replays its
// history; the client only sees a dropped connection.
func TestASessionOutlivesItsDaemon(t *testing.T) {
	shortTimeouts(t)
	dir := testStateDir(t)
	_, stop1 := startDaemonWith(t, dir, newEchoPty)

	conn, fr, ok := attachTo(t, dir, "s1")
	if !ok.Created {
		t.Fatal("the first attach must create the session")
	}
	send(t, conn, protocol.Frame{Type: protocol.TypeInput, Bytes: []byte("before-restart\n")})
	readUntil(t, conn, fr, "before-restart")

	stop1() // the old daemon goes; its host stays
	_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	conn.Close()
	if !hostAlive(dir, "s1") {
		t.Fatal("the session host must outlive the daemon")
	}

	d2, _ := startDaemonWith(t, dir, newEchoPty)
	t.Cleanup(func() { d2.hosts.closeAll() })
	conn2, fr2, ok2 := attachTo(t, dir, "s1")
	defer conn2.Close()
	if ok2.Created {
		t.Fatal("the new daemon must reattach to the live session, not create it")
	}
	readUntil(t, conn2, fr2, "before-restart") // the replay of the history
	send(t, conn2, protocol.Frame{Type: protocol.TypeInput, Bytes: []byte("after-restart\n")})
	readUntil(t, conn2, fr2, "after-restart")

	rep, err := queryStatus(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(rep.Sessions) != 1 || rep.Sessions[0].ID != "s1" || rep.Sessions[0].Agent != version || !rep.SessionHosts {
		t.Fatalf("status after the restart: %+v", rep)
	}
}

// The name the user gave a session lives in its host, so it outlives the
// daemon too.
func TestASessionKeepsItsNameAcrossDaemons(t *testing.T) {
	shortTimeouts(t)
	dir := testStateDir(t)
	_, stop1 := startDaemonWith(t, dir, newEchoPty)
	conn, _, _ := attachTo(t, dir, "s1")
	defer conn.Close()
	if renamed, err := renameSession(dir, "s1", "bG9ncw"); err != nil || !renamed { // "logs"
		t.Fatalf("renameSession = %v, %v", renamed, err)
	}
	stop1()

	d2, _ := startDaemonWith(t, dir, newEchoPty)
	t.Cleanup(func() { d2.hosts.closeAll() })
	rep, err := queryStatus(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(rep.Sessions) != 1 || rep.Sessions[0].Name != "logs" {
		t.Fatalf("after the restart: %+v", rep.Sessions)
	}
}

// Each session has its own host, and closing one leaves the other.
func TestEachSessionHasItsOwnHost(t *testing.T) {
	shortTimeouts(t)
	dir := testStateDir(t)
	d, _ := startDaemonWith(t, dir, newEchoPty)
	t.Cleanup(func() { d.hosts.closeAll() })

	c1, _, _ := attachTo(t, dir, "s1")
	defer c1.Close()
	c2, f2, _ := attachTo(t, dir, "s2")
	defer c2.Close()
	r1, _ := d.hosts.lookup("s1")
	r2, _ := d.hosts.lookup("s2")
	if r1.Port == r2.Port || r1.Token == r2.Token {
		t.Fatal("two sessions must not share a host")
	}

	if closed, err := closeSession(dir, "s1"); err != nil || !closed {
		t.Fatalf("closeSession = %v, %v", closed, err)
	}
	if hostAlive(dir, "s1") {
		t.Fatal("closing a session ends its host")
	}
	if _, err := os.Stat(filepath.Join(dir, hostRecordName("s1"))); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("the closed host's record must be gone: %v", err)
	}
	if !hostAlive(dir, "s2") {
		t.Fatal("the other session's host must stay")
	}
	send(t, c2, protocol.Frame{Type: protocol.TypeInput, Bytes: []byte("still-here\n")})
	readUntil(t, c2, f2, "still-here")
}

// A stop that closes the sessions (--stop) ends every host.
func TestStopClosesEveryHost(t *testing.T) {
	shortTimeouts(t)
	dir := testStateDir(t)
	d, err := startDaemon(dir, inProcessHosts(dir, newEchoPty))
	if err != nil {
		t.Fatal(err)
	}
	served := make(chan struct{})
	go func() { _ = d.serve(); close(served) }()
	c1, _, _ := attachTo(t, dir, "s1")
	defer c1.Close()
	c2, _, _ := attachTo(t, dir, "s2")
	defer c2.Close()

	if _, _, err := request(dir, controlRequest{Op: opStop}); err != nil {
		t.Fatal(err)
	}
	<-served
	if !d.closeSessions.Load() {
		t.Fatal("--stop must close the sessions")
	}
	if n := d.hosts.closeAll(); n != 2 {
		t.Fatalf("closed %d hosts, want 2", n)
	}
	d.close()
	d.conns.Wait()
	if hostAlive(dir, "s1") || hostAlive(dir, "s2") {
		t.Fatal("every host must be gone")
	}
	if recs := hostRecords(dir); len(recs) != 0 {
		t.Fatalf("records left: %v", recs)
	}
}

// A restart request ends the daemon but asks it to leave the hosts.
func TestRestartRequestLeavesTheHosts(t *testing.T) {
	shortTimeouts(t)
	dir := testStateDir(t)
	d, err := startDaemon(dir, inProcessHosts(dir, newEchoPty))
	if err != nil {
		t.Fatal(err)
	}
	served := make(chan struct{})
	go func() { _ = d.serve(); close(served) }()
	c, _, _ := attachTo(t, dir, "s1")
	defer c.Close()
	t.Cleanup(func() { d.hosts.closeAll() })

	if _, _, err := request(dir, controlRequest{Op: opRestart}); err != nil {
		t.Fatal(err)
	}
	<-served
	if !d.stopping.Load() || d.closeSessions.Load() {
		t.Fatal("a restart stops the daemon without closing the sessions")
	}
	d.close()
	d.conns.Wait()
	if !hostAlive(dir, "s1") {
		t.Fatal("the session host must stay")
	}
}

// A host whose shell exited and that no client reads ends on its own, and the
// daemon forgets it; the next HELLO for the id creates a new session.
func TestAnEndedSessionLetsItsHostGo(t *testing.T) {
	shortTimeouts(t)
	old := maintainInterval
	maintainInterval = 20 * time.Millisecond
	t.Cleanup(func() { maintainInterval = old })
	dir := testStateDir(t)
	d, _ := startDaemonWith(t, dir, exitPty)
	t.Cleanup(func() { d.hosts.closeAll() })

	c, _, _ := attachTo(t, dir, "s1")
	c.Close()
	waitFor(t, "the host to end", func() bool { return !hostAlive(dir, "s1") })
	d.hosts.prune()
	if _, ok := d.hosts.lookup("s1"); ok {
		t.Fatal("the daemon must forget an ended host")
	}
}

// A HELLO whose host cannot start its session is refused with the host's
// reason, and the daemon keeps no host for it.
func TestAHostThatCannotStartIsRefused(t *testing.T) {
	shortTimeouts(t)
	dir := testStateDir(t)
	failing := func(c, r uint16) (session.Pty, error) {
		return nil, &session.PtyError{Code: session.CodeNoConPty, Err: errors.New("too old")}
	}
	d, _ := startDaemonWith(t, dir, failing)
	st, _ := readState(dir)
	conn, err := dialDaemon(st)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	send(t, conn, protocol.Frame{Type: protocol.TypeHello, SessionID: "s1", Cols: 80, Rows: 24})
	f, err := (&frameReader{r: conn}).next()
	if err != nil {
		t.Fatal(err)
	}
	if f.Type != protocol.TypeBye || !strings.HasPrefix(f.Reason, "E_NO_CONPTY ") {
		t.Fatalf("got %+v, want a BYE with E_NO_CONPTY", f)
	}
	if _, ok := d.hosts.lookup("s1"); ok {
		t.Fatal("no host may stay for a session that never started")
	}
	if hostAlive(dir, "s1") {
		t.Fatal("the failed host must let go of its lock")
	}
}

// Only one host starts per id, however many HELLOs for it arrive at once.
func TestConcurrentHellosShareOneHost(t *testing.T) {
	shortTimeouts(t)
	dir := testStateDir(t)
	d, _ := startDaemonWith(t, dir, newEchoPty)
	t.Cleanup(func() { d.hosts.closeAll() })

	const n = 4
	created := make(chan bool, n)
	var wg sync.WaitGroup
	for range n {
		wg.Add(1)
		go func() {
			defer wg.Done()
			c, _, ok := attachTo(t, dir, "s1")
			created <- ok.Created
			c.Close()
		}()
	}
	wg.Wait()
	close(created)
	count := 0
	for c := range created {
		if c {
			count++
		}
	}
	if count != 1 {
		t.Fatalf("%d attaches said created, want exactly 1", count)
	}
}

// A daemon starting up drops the records of hosts that are gone.
func TestADaemonDropsTheRecordsOfDeadHosts(t *testing.T) {
	dir := testStateDir(t)
	rec := validState()
	rec.ID = "gone"
	if err := writeStateFile(dir, hostRecordName("gone"), rec); err != nil {
		t.Fatal(err)
	}
	startDaemonWith(t, dir, newEchoPty)
	if _, err := os.Stat(filepath.Join(dir, hostRecordName("gone"))); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("a dead host's record must be dropped: %v", err)
	}
}

// The control commands start a daemon when none runs but session hosts do,
// so the sessions stay visible and closable after the daemon was killed.
func TestControlCommandsBringBackADaemonForLiveHosts(t *testing.T) {
	shortTimeouts(t)
	dir := testStateDir(t)
	h, err := startHost(hostConfig{dir: dir, id: "s1", cols: 80, rows: 24, bufCap: 1 << 10}, newEchoPty)
	if err != nil {
		t.Fatal(err)
	}
	go h.run()
	t.Cleanup(h.end)
	spawned := false
	rep, err := statusWith(dir, func() error {
		spawned = true
		d, err := startDaemon(dir, inProcessHosts(dir, newEchoPty))
		if err != nil {
			return err
		}
		done := make(chan struct{})
		go func() { _ = d.serve(); close(done) }()
		t.Cleanup(func() {
			d.ln.Close()
			<-done
			d.conns.Wait()
			d.close()
		})
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if !spawned || rep.State != stateRunning || len(rep.Sessions) != 1 {
		t.Fatalf("spawned=%v status=%+v", spawned, rep)
	}
}

// statusWith is queryStatus with spawn in place of launching a daemon process.
func statusWith(dir string, spawn func() error) (statusReport, error) {
	old := spawnDaemonFunc
	spawnDaemonFunc = func(string) error { return spawn() }
	defer func() { spawnDaemonFunc = old }()
	return queryStatus(dir)
}

// --remove-unused keeps the binaries a live daemon or host runs from.
func TestRemoveUnusedKeepsBinariesInUse(t *testing.T) {
	dir := testStateDir(t)
	bin := t.TempDir()
	used := filepath.Join(bin, "agent-1.0.0-x")
	unused := filepath.Join(bin, "agent-0.9.0-x")
	for _, p := range []string{used, unused} {
		if err := os.WriteFile(p, []byte("x"), 0o700); err != nil {
			t.Fatal(err)
		}
	}
	lock, err := lockNamed(dir, hostLockName("s1"))
	if err != nil {
		t.Fatal(err)
	}
	defer lock.Close()
	rec := validState()
	rec.ID, rec.Exe = "s1", used
	if err := writeStateFile(dir, hostRecordName("s1"), rec); err != nil {
		t.Fatal(err)
	}
	missing := filepath.Join(bin, "agent-0.8.0-x")
	if err := removeUnused(dir, []string{used, unused, missing}); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(used); err != nil {
		t.Fatalf("a binary in use must stay: %v", err)
	}
	if _, err := os.Stat(unused); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("an unused binary must go: %v", err)
	}
}
