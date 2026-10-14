package main

import (
	"bufio"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/session"
)

// A session host (ADR-0022) is the process that holds one session: its PTY or
// ConPTY, the shell and the output ring buffer. The daemon starts it on the
// first HELLO for an id (--session-host) and from then on only routes to it,
// so the daemon can stop, crash or be replaced by another version while the
// session, and whatever runs in it, carries on.
//
// A host listens on its own loopback port with its own token, published in
// its record in the state dir (session-<key>.json), and holds its own lock
// (session-<key>.lock) for as long as it lives: a daemon that starts finds the
// live hosts there and routes to them again. It takes two kinds of connection,
// each behind the handshake of rendezvous.go: a session one, which carries the
// client protocol from the HELLO on exactly as the daemon got it, and a
// control one, with one JSON request and one JSON reply (info, tail, close).
//
// What the daemon and a host say to each other must keep working across
// versions: a daemon routes to the hosts of older versions it finds. So it
// only ever grows, with optional fields, and hostProto says which revision a
// host speaks.
//
// A host never ends by time (ADR-0014): only when its shell exits and no
// client is still reading, or when the user closes its session.

const (
	hostSessionMagic = "TTNAHSS1"
	hostControlMagic = "TTNAHCT1"
)

// hostProto is the revision of the daemon↔host protocol this agent speaks.
const hostProto = 1

// Names of a host's files in the state dir. The key is derived from the
// session id, which comes from the client: never used as a file name as is.
const (
	hostFilePrefix = "session-"
	hostRecordExt  = ".json"
	hostLockExt    = ".lock"
)

func hostKey(id string) string {
	sum := sha256.Sum256([]byte(id))
	return hex.EncodeToString(sum[:16])
}

func hostRecordName(id string) string { return hostFilePrefix + hostKey(id) + hostRecordExt }
func hostLockName(id string) string   { return hostFilePrefix + hostKey(id) + hostLockExt }

// Host control operations.
const (
	hostOpInfo  = "info"
	hostOpTail  = "tail"
	hostOpClose = "close"
)

type hostRequest struct {
	Op    string `json:"op"`
	Bytes int    `json:"bytes,omitempty"` // hostOpTail: how much history
}

type hostReply struct {
	Error string         `json:"error,omitempty"`
	Info  *hostInfo      `json:"info,omitempty"`
	Tail  *previewReport `json:"tail,omitempty"`
}

// hostInfo is what a host tells about its session. Times are Unix
// milliseconds on the host's clock, which is the daemon's.
type hostInfo struct {
	ID            string `json:"id"`
	Agent         string `json:"agent"` // the host's version
	PID           int    `json:"pid"`   // the host process
	CreatedMs     int64  `json:"createdMs"`
	LastUsedMs    int64  `json:"lastUsedMs"`
	DetachedMs    int64  `json:"detachedMs,omitempty"`
	Clients       int    `json:"clients"`
	Closed        bool   `json:"closed,omitempty"`
	BufferBytes   uint64 `json:"bufferBytes"`
	ShellPID      int    `json:"shellPid,omitempty"`
	Shell         string `json:"shell,omitempty"`
	ForegroundPID int    `json:"foregroundPid,omitempty"`
	LastOutputMs  int64  `json:"lastOutputMs,omitempty"`
	Cols          uint16 `json:"cols,omitempty"`
	Rows          uint16 `json:"rows,omitempty"`
	Title         string `json:"title,omitempty"`
}

// sessionInfo turns a host's report back into what sessionFrom expects.
func (h hostInfo) sessionInfo() session.Info {
	in := session.Info{
		ID: h.ID, Created: time.UnixMilli(h.CreatedMs), LastUsed: time.UnixMilli(h.LastUsedMs),
		Clients: h.Clients, Closed: h.Closed, BufferBytes: h.BufferBytes,
		ShellPID: h.ShellPID, Shell: h.Shell, ForegroundPID: h.ForegroundPID,
		Cols: h.Cols, Rows: h.Rows, Title: h.Title,
	}
	if h.DetachedMs != 0 {
		in.Detached = time.UnixMilli(h.DetachedMs)
	}
	if h.LastOutputMs != 0 {
		in.LastOutput = time.UnixMilli(h.LastOutputMs)
	}
	return in
}

func infoOf(in session.Info) hostInfo {
	h := hostInfo{
		ID: in.ID, Agent: version, PID: os.Getpid(),
		CreatedMs: in.Created.UnixMilli(), LastUsedMs: in.LastUsed.UnixMilli(),
		Clients: in.Clients, Closed: in.Closed, BufferBytes: in.BufferBytes,
		ShellPID: in.ShellPID, Shell: in.Shell, ForegroundPID: in.ForegroundPID,
		Cols: in.Cols, Rows: in.Rows, Title: in.Title,
	}
	if !in.Detached.IsZero() {
		h.DetachedMs = in.Detached.UnixMilli()
	}
	if !in.LastOutput.IsZero() {
		h.LastOutputMs = in.LastOutput.UnixMilli()
	}
	return h
}

// hostConfig is what a host is started with.
type hostConfig struct {
	dir        string
	id         string
	cols, rows uint16
	bufCap     int
}

// hostAnnounce is the one line a host writes to whoever started it once it
// is ready, or once it knows it cannot be: its record, or why not as
// `<code> <message>` of the error contract.
type hostAnnounce struct {
	State *agentState `json:"state,omitempty"`
	Error string      `json:"error,omitempty"`
}

// sessionHost is a running host.
type sessionHost struct {
	cfg     hostConfig
	lock    *os.File
	ln      net.Listener
	token   []byte
	record  agentState
	reg     *session.Registry
	fresh   atomic.Bool // the next attach is the first: HELLO_OK says created
	closing atomic.Bool
	slots   handshakeSlots
	quit    chan struct{}
	once    sync.Once

	mu    sync.Mutex
	conns map[net.Conn]struct{}
	wg    sync.WaitGroup // connection goroutines
}

// runSessionHost is --session-host: it holds one session until the session
// ends, announcing on stdout once it is ready.
func runSessionHost(cfg hostConfig) error {
	if exe, err := os.Executable(); err == nil {
		_ = os.Setenv(agentPathEnv, exe)
	}
	return runHost(cfg, session.NewPty, func(a hostAnnounce) {
		data, _ := json.Marshal(a)
		_, _ = os.Stdout.Write(append(data, '\n'))
		// Nothing else is ever written there: whoever started the host may
		// be gone by the time the session ends.
		_ = os.Stdout.Close()
	})
}

// runHost starts the session with newPty, calls announce exactly once, and
// serves until the session ends. It returns nil when the session ended
// normally, and the reason it never started otherwise.
func runHost(cfg hostConfig, newPty session.PtyFactory, announce func(hostAnnounce)) error {
	h, err := startHost(cfg, newPty)
	if err != nil {
		var ae *agentError
		if !errors.As(err, &ae) {
			err = withCode(session.CodePty, err)
			errors.As(err, &ae)
		}
		announce(hostAnnounce{Error: ae.contractReason()})
		return err
	}
	rec := h.record
	announce(hostAnnounce{State: &rec})
	h.run()
	return nil
}

// startHost takes the session's lock, creates its PTY, listens and publishes
// its record, in that order: a record always belongs to a live host with a
// session.
func startHost(cfg hostConfig, newPty session.PtyFactory) (*sessionHost, error) {
	if err := ensureStateDir(cfg.dir); err != nil {
		return nil, withCode(codeStateDir, err)
	}
	lock, err := lockNamed(cfg.dir, hostLockName(cfg.id))
	if errors.Is(err, errLocked) {
		return nil, withCode(codeLock, errors.New("another process already holds this session"))
	}
	if err != nil {
		return nil, withCode(codeLock, err)
	}
	reg := session.NewRegistry(newPty, cfg.bufCap)
	if _, _, err := reg.AttachOrCreate(cfg.id, cfg.cols, cfg.rows); err != nil {
		lock.Close()
		var pe *session.PtyError
		if errors.As(err, &pe) {
			return nil, withCode(pe.Code, pe.Err)
		}
		return nil, withCode(session.CodePty, err)
	}
	fail := func(err error) (*sessionHost, error) {
		reg.CloseAll()
		lock.Close()
		return nil, err
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return fail(err)
	}
	token := make([]byte, tokenLen)
	if _, err := rand.Read(token); err != nil {
		ln.Close()
		return fail(err)
	}
	exe, _ := os.Executable()
	rec := agentState{
		Schema:    stateSchema,
		Agent:     version,
		Port:      ln.Addr().(*net.TCPAddr).Port,
		Token:     hex.EncodeToString(token),
		PID:       os.Getpid(),
		ID:        cfg.id,
		Exe:       exe,
		HostProto: hostProto,
	}
	if err := writeStateFile(cfg.dir, hostRecordName(cfg.id), rec); err != nil {
		ln.Close()
		return fail(err)
	}
	h := &sessionHost{
		cfg: cfg, lock: lock, ln: ln, token: token, record: rec, reg: reg,
		slots: newHandshakeSlots(), quit: make(chan struct{}), conns: map[net.Conn]struct{}{},
	}
	h.fresh.Store(true)
	return h, nil
}

// run serves until the session ends, then lets go of everything.
func (h *sessionHost) run() {
	var mwg sync.WaitGroup
	mwg.Add(1)
	go func() {
		defer mwg.Done()
		h.maintain()
	}()
	h.serve()
	h.end()
	mwg.Wait()
	h.wg.Wait()
	removeOwnStateFile(h.cfg.dir, hostRecordName(h.cfg.id), os.Getpid())
	h.lock.Close()
}

// end stops serving: the listener closes, every connection closes and the
// session's PTY closes, ending its shell if it still runs.
func (h *sessionHost) end() {
	h.once.Do(func() {
		h.closing.Store(true)
		close(h.quit)
		h.ln.Close()
		h.mu.Lock()
		for c := range h.conns {
			c.Close()
		}
		h.mu.Unlock()
		h.reg.CloseAll()
	})
}

// maintain ends the host once the shell has exited and no client is still
// reading its last output, and puts the record back if it went missing.
func (h *sessionHost) maintain() {
	t := time.NewTicker(maintainInterval)
	defer t.Stop()
	for {
		select {
		case <-h.quit:
			return
		case <-t.C:
			if h.reg.GC() > 0 {
				h.end()
				return
			}
			if st, err := readStateFile(h.cfg.dir, hostRecordName(h.cfg.id)); err != nil || st != h.record {
				_ = writeStateFile(h.cfg.dir, hostRecordName(h.cfg.id), h.record)
			}
		}
	}
}

func (h *sessionHost) serve() {
	for {
		conn, err := h.ln.Accept()
		if errors.Is(err, net.ErrClosed) {
			return
		}
		if err != nil {
			time.Sleep(100 * time.Millisecond)
			continue
		}
		if !h.slots.take() {
			conn.Close()
			continue
		}
		if !h.track(conn) {
			h.slots.release()
			conn.Close()
			return
		}
		h.wg.Add(1)
		go func() {
			defer h.wg.Done()
			defer h.untrack(conn)
			magic, err := acceptMagics(conn, h.token, []string{hostSessionMagic, hostControlMagic})
			h.slots.release()
			if err != nil {
				conn.Close()
				return
			}
			if magic == hostControlMagic {
				h.serveControl(conn)
				return
			}
			serveConn(conn, h.attach)
		}()
	}
}

func (h *sessionHost) track(c net.Conn) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.closing.Load() {
		return false
	}
	h.conns[c] = struct{}{}
	return true
}

func (h *sessionHost) untrack(c net.Conn) {
	h.mu.Lock()
	delete(h.conns, c)
	h.mu.Unlock()
}

// attach is serveConn's way into the one session this host holds. HELLO_OK
// says created only to the first attach, which is the one the daemon started
// the host for.
func (h *sessionHost) attach(id string, cols, rows uint16) (*session.Session, bool, error) {
	if id != h.cfg.id {
		return nil, false, withCode(session.CodePty, errors.New("this session host holds another session"))
	}
	if h.closing.Load() {
		return nil, false, withCode(session.CodePty, errors.New("the session is closing"))
	}
	s, ok := h.reg.Get(id)
	if !ok {
		return nil, false, withCode(session.CodePty, errors.New("the session has ended"))
	}
	return s, h.fresh.Swap(false), nil
}

func (h *sessionHost) serveControl(conn net.Conn) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(helloTimeout))
	line, err := bufio.NewReaderSize(io.LimitReader(conn, controlLineMax), controlLineMax).ReadBytes('\n')
	if err != nil {
		return
	}
	var req hostRequest
	if err := json.Unmarshal(line, &req); err != nil {
		writeHostReply(conn, hostReply{Error: "malformed request"})
		return
	}
	_ = conn.SetDeadline(time.Now().Add(controlTimeout))
	switch req.Op {
	case hostOpInfo:
		list := h.reg.List()
		if len(list) == 0 {
			writeHostReply(conn, hostReply{Error: errNoSession.Error()})
			return
		}
		in := infoOf(list[0])
		writeHostReply(conn, hostReply{Info: &in})
	case hostOpTail:
		data, cols, rows, ok := h.reg.Tail(h.cfg.id, req.Bytes)
		if !ok {
			writeHostReply(conn, hostReply{Error: errNoSession.Error()})
			return
		}
		writeHostReply(conn, hostReply{Tail: &previewReport{Cols: cols, Rows: rows, Data: data}})
	case hostOpClose:
		// The reply goes first, so the caller knows the host took it.
		writeHostReply(conn, hostReply{})
		h.end()
	default:
		writeHostReply(conn, hostReply{Error: "unknown op " + req.Op})
	}
}

func writeHostReply(w io.Writer, r hostReply) {
	data, _ := json.Marshal(r)
	_, _ = w.Write(append(data, '\n'))
}

// hostRequestTo sends one control request to the host rec describes.
func hostRequestTo(rec agentState, req hostRequest) (hostReply, error) {
	var reply hostReply
	conn, err := dialWith(rec, hostControlMagic)
	if err != nil {
		return reply, err
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(controlTimeout))
	data, _ := json.Marshal(req)
	if _, err := conn.Write(append(data, '\n')); err != nil {
		return reply, err
	}
	line, err := bufio.NewReader(conn).ReadBytes('\n')
	if err != nil {
		return reply, fmt.Errorf("no reply from the session host: %w", err)
	}
	if err := json.Unmarshal(line, &reply); err != nil {
		return reply, fmt.Errorf("unreadable reply from the session host: %w", err)
	}
	if reply.Error != "" {
		return reply, errors.New(reply.Error)
	}
	return reply, nil
}

// hostRecords lists the session host records in dir, keyed by file name.
// Unreadable ones are left out.
func hostRecords(dir string) map[string]agentState {
	out := map[string]agentState{}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return out
	}
	for _, e := range entries {
		name := e.Name()
		if e.IsDir() || !strings.HasPrefix(name, hostFilePrefix) || filepath.Ext(name) != hostRecordExt {
			continue
		}
		if st, err := readStateFile(dir, name); err == nil && st.ID != "" && name == hostRecordName(st.ID) {
			out[name] = st
		}
	}
	return out
}

// hostAlive reports whether the host of session id still holds its lock.
func hostAlive(dir, id string) bool {
	held, err := lockNamedHeld(dir, hostLockName(id))
	return err == nil && held
}

// removeStaleHost drops the record and lock file of session id, whose host
// is gone. Only the daemon calls it, and it is the only one that starts
// hosts, so no host of that id can be starting meanwhile.
func removeStaleHost(dir, id string) {
	_ = os.Remove(filepath.Join(dir, hostRecordName(id)))
	_ = os.Remove(filepath.Join(dir, hostLockName(id)))
}
