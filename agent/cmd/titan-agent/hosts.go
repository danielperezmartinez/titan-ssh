package main

import (
	"bufio"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"strconv"
	"sync"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/protocol"
)

// The daemon's side of the session hosts (ADR-0022, host.go): which host
// holds each session, starting one on the first HELLO for an id, and routing
// every session connection to its host untouched.

// hostStartWait bounds how long the daemon waits for a host it started to
// announce itself. A var so tests can shorten it.
var hostStartWait = 10 * time.Second

// hostCloseWait bounds how long closing every session waits for the hosts to
// let go of their locks.
var hostCloseWait = 5 * time.Second

// hostSpawner starts the host of session id at the given size and returns
// its record once it serves, or the reason it could not start.
type hostSpawner func(id string, cols, rows uint16) (agentState, error)

// reasonError is a failure that already reads as `<code> <message>` of the
// error contract: what a host that could not start announced.
type reasonError string

func (e reasonError) Error() string { return string(e) }

// processHostSpawner starts each host as a process of this same executable,
// detached like the daemon, so it outlives the daemon.
func processHostSpawner(dir string, bufCap int) hostSpawner {
	return func(id string, cols, rows uint16) (agentState, error) {
		exe, err := os.Executable()
		if err != nil {
			return agentState{}, err
		}
		attr, err := detachAttr()
		if err != nil {
			return agentState{}, err
		}
		// `=` keeps an id that starts with a dash from reading as a flag.
		cmd := exec.Command(exe, "--session-host="+id, "--state-dir", dir,
			"--buffer-bytes", strconv.Itoa(bufCap),
			"--cols", strconv.Itoa(int(cols)), "--rows", strconv.Itoa(int(rows)))
		cmd.SysProcAttr = attr
		stdout, err := cmd.StdoutPipe()
		if err != nil {
			return agentState{}, err
		}
		if err := cmd.Start(); err != nil {
			return agentState{}, spawnFailure(attr, err)
		}
		line, err := readLineWithin(stdout, hostStartWait)
		if err != nil {
			_ = cmd.Process.Kill()
			_ = cmd.Wait()
			return agentState{}, fmt.Errorf("the session host did not start: %w", err)
		}
		// Reap it whenever it ends, while this daemon lives.
		go func() { _ = cmd.Wait() }()
		return parseAnnounce(line)
	}
}

// readLineWithin reads one line from r, giving up after d.
func readLineWithin(r io.Reader, d time.Duration) ([]byte, error) {
	type result struct {
		line []byte
		err  error
	}
	ch := make(chan result, 1)
	go func() {
		line, err := bufio.NewReader(r).ReadBytes('\n')
		ch <- result{line, err}
	}()
	select {
	case res := <-ch:
		return res.line, res.err
	case <-time.After(d):
		return nil, fmt.Errorf("no answer within %v", d)
	}
}

func parseAnnounce(line []byte) (agentState, error) {
	var a hostAnnounce
	if err := json.Unmarshal(line, &a); err != nil {
		return agentState{}, fmt.Errorf("unreadable answer from the session host: %w", err)
	}
	if a.Error != "" {
		return agentState{}, reasonError(a.Error)
	}
	if a.State == nil {
		return agentState{}, errors.New("the session host gave no record")
	}
	return *a.State, nil
}

// hostTable is the daemon's map of session ids to their hosts.
type hostTable struct {
	dir   string
	spawn hostSpawner

	mu    sync.Mutex
	hosts map[string]*hostEntry
}

// hostEntry is one session's host; ready closes once rec or err is set.
type hostEntry struct {
	ready chan struct{}
	rec   agentState
	err   error
}

func newHostTable(dir string, spawn hostSpawner) *hostTable {
	return &hostTable{dir: dir, spawn: spawn, hosts: map[string]*hostEntry{}}
}

// adopt takes on the hosts already running for this user, left by a daemon
// that stopped, crashed or was replaced, and drops the records of hosts that
// are gone.
func (t *hostTable) adopt() {
	for _, rec := range hostRecords(t.dir) {
		if !hostAlive(t.dir, rec.ID) {
			removeStaleHost(t.dir, rec.ID)
			continue
		}
		e := &hostEntry{ready: make(chan struct{}), rec: rec}
		close(e.ready)
		t.mu.Lock()
		t.hosts[rec.ID] = e
		t.mu.Unlock()
	}
}

// get returns the host of session id, starting one if there is none. Only
// one start runs per id; concurrent callers wait for it.
func (t *hostTable) get(id string, cols, rows uint16) (agentState, error) {
	t.mu.Lock()
	e, ok := t.hosts[id]
	if !ok {
		e = &hostEntry{ready: make(chan struct{})}
		t.hosts[id] = e
	}
	t.mu.Unlock()
	if !ok {
		e.rec, e.err = t.spawn(id, cols, rows)
		if e.err != nil {
			t.mu.Lock()
			delete(t.hosts, id)
			t.mu.Unlock()
		}
		close(e.ready)
	}
	<-e.ready
	return e.rec, e.err
}

// lookup returns the host of session id, if it has one ready.
func (t *hostTable) lookup(id string) (agentState, bool) {
	t.mu.Lock()
	e, ok := t.hosts[id]
	t.mu.Unlock()
	if !ok {
		return agentState{}, false
	}
	select {
	case <-e.ready:
		return e.rec, e.err == nil
	default:
		return agentState{}, false
	}
}

// all returns every ready host.
func (t *hostTable) all() []agentState {
	t.mu.Lock()
	defer t.mu.Unlock()
	var out []agentState
	for _, e := range t.hosts {
		select {
		case <-e.ready:
			if e.err == nil {
				out = append(out, e.rec)
			}
		default:
		}
	}
	return out
}

// forget drops session id's host if it is still the one rec describes.
func (t *hostTable) forget(id string, rec agentState) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if e, ok := t.hosts[id]; ok {
		select {
		case <-e.ready:
			if e.rec == rec {
				delete(t.hosts, id)
			}
		default:
		}
	}
}

// prune forgets the hosts that have ended (their session ended or was
// closed) and removes what they left in the state dir.
func (t *hostTable) prune() {
	for _, rec := range t.all() {
		if !hostAlive(t.dir, rec.ID) {
			t.forget(rec.ID, rec)
			removeStaleHost(t.dir, rec.ID)
		}
	}
}

// close ends session id's host, which closes the session. It reports whether
// there was one.
func (t *hostTable) close(id string) bool {
	rec, ok := t.lookup(id)
	if !ok {
		return false
	}
	_, err := hostRequestTo(rec, hostRequest{Op: hostOpClose})
	t.forget(id, rec)
	if err == nil {
		t.waitGone([]agentState{rec}, hostCloseWait)
	}
	removeStaleHostIfGone(t.dir, rec.ID)
	return true
}

// rename gives session id the name the user chose; it reports whether the
// session has a host that took it.
func (t *hostTable) rename(id, name string) bool {
	rec, ok := t.lookup(id)
	if !ok {
		return false
	}
	reply, err := hostRequestTo(rec, hostRequest{Op: hostOpRename, Name: name})
	return err == nil && reply.Renamed
}

// closeAll ends every host, closing every session.
func (t *hostTable) closeAll() int {
	all := t.all()
	var wg sync.WaitGroup
	for _, rec := range all {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, _ = hostRequestTo(rec, hostRequest{Op: hostOpClose})
			t.forget(rec.ID, rec)
		}()
	}
	wg.Wait()
	t.waitGone(all, hostCloseWait)
	for _, rec := range all {
		removeStaleHostIfGone(t.dir, rec.ID)
	}
	return len(all)
}

// waitGone waits until none of hosts holds its lock, or timeout.
func (t *hostTable) waitGone(hosts []agentState, timeout time.Duration) {
	deadline := time.Now().Add(timeout)
	for _, rec := range hosts {
		for hostAlive(t.dir, rec.ID) && time.Now().Before(deadline) {
			time.Sleep(20 * time.Millisecond)
		}
	}
}

func removeStaleHostIfGone(dir, id string) {
	if !hostAlive(dir, id) {
		removeStaleHost(dir, id)
	}
}

// connect returns a session connection to the host of session id, starting
// the host if needed. A host found gone (its process ended without the daemon
// noticing yet) is replaced by a new one.
func (t *hostTable) connect(id string, cols, rows uint16) (net.Conn, error) {
	var err error
	for range 2 {
		var rec agentState
		rec, err = t.get(id, cols, rows)
		if err != nil {
			return nil, err
		}
		var conn net.Conn
		conn, err = dialWith(rec, hostSessionMagic)
		if err == nil {
			return conn, nil
		}
		if hostAlive(t.dir, id) {
			return nil, fmt.Errorf("the session host does not answer: %w", err)
		}
		t.forget(id, rec)
		removeStaleHost(t.dir, id)
	}
	return nil, err
}

// track records a routed connection pair, or reports false once the daemon
// has closed.
func (d *daemon) track(conns ...net.Conn) bool {
	d.routeMu.Lock()
	defer d.routeMu.Unlock()
	if d.routes == nil {
		return false
	}
	for _, c := range conns {
		d.routes[c] = struct{}{}
	}
	return true
}

func (d *daemon) untrack(conns ...net.Conn) {
	d.routeMu.Lock()
	defer d.routeMu.Unlock()
	for _, c := range conns {
		delete(d.routes, c)
	}
}

// maxHelloPayload bounds the HELLO the daemon reads before routing: an id and
// a few numbers.
const maxHelloPayload = 64 * 1024

// routeConn runs one client session connection on the daemon: it reads the
// opening HELLO to learn the session id, connects to that session's host
// (starting it on first use) and from then on only copies bytes both ways,
// the HELLO included, so the client protocol is the host's business.
func (d *daemon) routeConn(conn net.Conn) {
	defer conn.Close()
	// A client that connects but never says HELLO must not pin the connection.
	_ = conn.SetReadDeadline(time.Now().Add(helloTimeout))
	header := make([]byte, 5)
	if _, err := io.ReadFull(conn, header); err != nil {
		return
	}
	length := binary.BigEndian.Uint32(header[1:5])
	if protocol.Type(header[0]) != protocol.TypeHello || length > maxHelloPayload {
		return // protocol violation: HELLO must come first
	}
	raw := make([]byte, 5+int(length))
	copy(raw, header)
	if _, err := io.ReadFull(conn, raw[5:]); err != nil {
		return
	}
	frames, err := (&protocol.Decoder{}).Feed(raw)
	if err != nil || len(frames) != 1 {
		return
	}
	hello := frames[0]
	_ = conn.SetReadDeadline(time.Time{})

	hc, err := d.hosts.connect(hello.SessionID, clampSize(hello.Cols, 80), clampSize(hello.Rows, 24))
	if err != nil {
		// The front is only splicing bytes by now and has nothing to report,
		// so the reason travels in the BYE instead of on the front's stderr.
		_, _ = conn.Write(protocol.Encode(protocol.Frame{Type: protocol.TypeBye, Reason: attachFailure(err)}))
		return
	}
	defer hc.Close()
	if !d.track(conn, hc) {
		return // the daemon is closing
	}
	defer d.untrack(conn, hc)
	if _, err := hc.Write(raw); err != nil {
		return
	}
	done := make(chan struct{}, 2)
	go func() { _, _ = io.Copy(hc, conn); done <- struct{}{} }()
	go func() { _, _ = io.Copy(conn, hc); done <- struct{}{} }()
	<-done
	// Either side ending ends the route: closing both unblocks the other copy.
	conn.Close()
	hc.Close()
	<-done
}
