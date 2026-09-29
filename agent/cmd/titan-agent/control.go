package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net"
	"os"
	"runtime"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/procmem"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/session"
)

// The control connection lets the user see and close what the daemon holds
// (task "Transparencia y control del agente en el destino"): --status,
// --close-session and an orderly --stop. It runs over the same loopback +
// token rendezvous as a session, with its own preamble magic, and carries one
// JSON request line and one JSON reply line. The client↔agent protocol on the
// SSH channel is unchanged; the client reaches this by exec'ing the CLI.

// controlLineMax bounds a request line, so a caller cannot make the daemon
// buffer without limit.
const controlLineMax = 4096

const (
	opStatus = "status"
	opClose  = "close"
	opStop   = "stop"
)

type controlRequest struct {
	Op      string `json:"op"`
	Session string `json:"session,omitempty"`
}

type controlReply struct {
	Error  string        `json:"error,omitempty"`
	Status *statusReport `json:"status,omitempty"`
	Closed bool          `json:"closed,omitempty"` // opClose: the session existed
}

// Daemon states in a statusReport.
const (
	stateRunning     = "running"     // a daemon answered with its status
	stateStopped     = "stopped"     // no daemon runs for this user
	stateLegacy      = "legacy"      // a daemon runs but predates the control connection
	stateUnreachable = "unreachable" // a daemon holds the lock but does not answer
)

// statusReport is the --status --json contract the client parses (schema 1).
// Times are Unix milliseconds on the destination's clock; the client compares
// them with nowMs, never with its own clock.
type statusReport struct {
	Schema int    `json:"schema"`
	State  string `json:"state"`
	// The version of the daemon: from its reply, or from agent.json for a
	// legacy daemon. CLI is this binary's version.
	Agent     string `json:"agent,omitempty"`
	CLI       string `json:"cli"`
	PID       int    `json:"pid,omitempty"`
	OS        string `json:"os,omitempty"`
	Arch      string `json:"arch,omitempty"`
	NowMs     int64  `json:"nowMs"`
	StartedMs int64  `json:"startedMs,omitempty"`
	// MemoryBytes is the daemon and every process under it; absent when the
	// system does not let the agent measure it.
	MemoryBytes *uint64         `json:"memoryBytes,omitempty"`
	Sessions    []sessionReport `json:"sessions"`
}

type sessionReport struct {
	ID         string `json:"id"`
	CreatedMs  int64  `json:"createdMs"`
	LastUsedMs int64  `json:"lastUsedMs"`
	// DetachedMs is when the last client left; absent while one is attached.
	DetachedMs  int64   `json:"detachedMs,omitempty"`
	Clients     int     `json:"clients"`
	Closed      bool    `json:"closed,omitempty"`
	BufferBytes uint64  `json:"bufferBytes"`
	MemoryBytes *uint64 `json:"memoryBytes,omitempty"` // the shell and its descendants
}

const statusSchema = 1

// serveControl answers one control request. A stop reply is sent before
// stop runs, so the caller knows the daemon took it.
func (d *daemon) serveControl(conn net.Conn) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(helloTimeout))
	var req controlRequest
	line, err := bufio.NewReaderSize(io.LimitReader(conn, controlLineMax), controlLineMax).ReadBytes('\n')
	if err != nil {
		return
	}
	if err := json.Unmarshal(line, &req); err != nil {
		writeReply(conn, controlReply{Error: "malformed request"})
		return
	}
	switch req.Op {
	case opStatus:
		st := d.status()
		writeReply(conn, controlReply{Status: &st})
	case opClose:
		writeReply(conn, controlReply{Closed: d.reg.Close(req.Session)})
	case opStop:
		writeReply(conn, controlReply{})
		d.stop()
	default:
		writeReply(conn, controlReply{Error: "unknown op " + req.Op})
	}
}

func writeReply(w io.Writer, r controlReply) {
	data, _ := json.Marshal(r)
	_, _ = w.Write(append(data, '\n'))
}

// status reports the daemon and its sessions as they are now.
func (d *daemon) status() statusReport {
	now := time.Now()
	st := statusReport{
		Schema:    statusSchema,
		State:     stateRunning,
		Agent:     version,
		CLI:       version,
		PID:       os.Getpid(),
		OS:        runtime.GOOS,
		Arch:      runtime.GOARCH,
		NowMs:     now.UnixMilli(),
		StartedMs: d.started.UnixMilli(),
		Sessions:  []sessionReport{},
	}
	table, _ := procmem.Snapshot() // nil when the system cannot be measured
	if table != nil {
		if b, ok := table.TreeBytes(os.Getpid()); ok {
			st.MemoryBytes = &b
		}
	}
	for _, in := range d.reg.List() {
		st.Sessions = append(st.Sessions, sessionFrom(in, table))
	}
	return st
}

func sessionFrom(in session.Info, table *procmem.Table) sessionReport {
	r := sessionReport{
		ID:          in.ID,
		CreatedMs:   in.Created.UnixMilli(),
		LastUsedMs:  in.LastUsed.UnixMilli(),
		Clients:     in.Clients,
		Closed:      in.Closed,
		BufferBytes: in.BufferBytes,
	}
	if !in.Detached.IsZero() {
		r.DetachedMs = in.Detached.UnixMilli()
	}
	if table != nil && in.ShellPID > 0 {
		if b, ok := table.TreeBytes(in.ShellPID); ok {
			r.MemoryBytes = &b
		}
	}
	return r
}

// errLegacy reports a daemon that answers sessions but not control requests:
// it predates them.
var errLegacy = errors.New("the running daemon predates control requests")

// request sends one control request to the daemon published in stateDir. It
// returns fs.ErrNotExist when no state file exists, errLegacy for an older
// daemon, and the dial error when nobody answers.
func request(stateDir string, req controlRequest) (controlReply, agentState, error) {
	var reply controlReply
	st, err := readState(stateDir)
	if err != nil {
		return reply, st, err
	}
	conn, err := dialControl(st)
	if errors.Is(err, errRejected) {
		// The control magic was refused. If a session preamble is accepted,
		// the daemon is alive and simply older.
		if c, derr := dialDaemon(st); derr == nil {
			c.Close()
			return reply, st, errLegacy
		}
	}
	if err != nil {
		return reply, st, err
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(controlTimeout))
	data, _ := json.Marshal(req)
	if _, err := conn.Write(append(data, '\n')); err != nil {
		return reply, st, err
	}
	line, err := bufio.NewReader(conn).ReadBytes('\n')
	if err != nil {
		return reply, st, fmt.Errorf("no reply from the daemon: %w", err)
	}
	if err := json.Unmarshal(line, &reply); err != nil {
		return reply, st, fmt.Errorf("unreadable reply from the daemon: %w", err)
	}
	if reply.Error != "" {
		return reply, st, errors.New(reply.Error)
	}
	return reply, st, nil
}

// controlTimeout bounds a control request once connected: a status snapshot
// walks the process table, which takes well under this.
var controlTimeout = 10 * time.Second

// queryStatus is --status: the daemon's own report when it answers, otherwise
// what can be told from the state dir (stopped, legacy or unreachable).
func queryStatus(stateDir string) (statusReport, error) {
	if err := ensureStateDir(stateDir); err != nil {
		return statusReport{}, withCode(codeStateDir, err)
	}
	st := statusReport{Schema: statusSchema, CLI: version, NowMs: time.Now().UnixMilli(), Sessions: []sessionReport{}}
	reply, rec, err := request(stateDir, controlRequest{Op: opStatus})
	switch {
	case err == nil && reply.Status != nil:
		reply.Status.CLI = version
		return *reply.Status, nil
	case errors.Is(err, errLegacy):
		st.State, st.Agent, st.PID = stateLegacy, rec.Agent, rec.PID
		return st, nil
	}
	held, lerr := lockHeld(stateDir)
	if lerr != nil {
		return st, withCode(codeLock, lerr)
	}
	if held {
		st.State = stateUnreachable
		if err == nil || !errors.Is(err, fs.ErrNotExist) {
			st.Agent, st.PID = rec.Agent, rec.PID
		}
	} else {
		st.State = stateStopped
	}
	return st, nil
}

// closeSession is --close-session: it ends one session and reports whether it
// existed. With no daemon running there is nothing to close, which is not an
// error: the session is gone either way.
func closeSession(stateDir, id string) (bool, error) {
	if err := ensureStateDir(stateDir); err != nil {
		return false, withCode(codeStateDir, err)
	}
	reply, _, err := request(stateDir, controlRequest{Op: opClose, Session: id})
	if err == nil {
		return reply.Closed, nil
	}
	if errors.Is(err, errLegacy) {
		return false, err
	}
	held, lerr := lockHeld(stateDir)
	if lerr != nil {
		return false, withCode(codeLock, lerr)
	}
	if held {
		return false, err
	}
	return false, nil
}

// printStatus writes st for a person.
func printStatus(w io.Writer, st statusReport) {
	now := time.UnixMilli(st.NowMs)
	switch st.State {
	case stateStopped:
		fmt.Fprintln(w, "titan-agent: no daemon is running")
		return
	case stateLegacy:
		fmt.Fprintf(w, "titan-agent %s (PID %d) is running but is too old to report its sessions\n", st.Agent, st.PID)
		fmt.Fprintln(w, "stop it with --stop; the next connection starts the current version")
		return
	case stateUnreachable:
		fmt.Fprintln(w, "titan-agent: a daemon holds the lock but does not answer")
		if st.PID > 0 {
			fmt.Fprintf(w, "its last record says titan-agent %s, PID %d\n", st.Agent, st.PID)
		}
		return
	}
	fmt.Fprintf(w, "titan-agent %s · PID %d · %s/%s · up %s\n",
		st.Agent, st.PID, st.OS, st.Arch, ago(now, st.StartedMs))
	if st.MemoryBytes != nil {
		fmt.Fprintf(w, "memory: %s (daemon and its sessions)\n", humanBytes(*st.MemoryBytes))
	}
	fmt.Fprintf(w, "sessions: %d\n", len(st.Sessions))
	for _, s := range st.Sessions {
		state := fmt.Sprintf("attached (%d)", s.Clients)
		switch {
		case s.Closed:
			state = "shell exited"
		case s.Clients == 0:
			state = "detached " + ago(now, s.DetachedMs)
		}
		mem := "?"
		if s.MemoryBytes != nil {
			mem = humanBytes(*s.MemoryBytes)
		}
		fmt.Fprintf(w, "  %s  %s · created %s ago · memory %s · history %s\n",
			s.ID, state, ago(now, s.CreatedMs), mem, humanBytes(s.BufferBytes))
	}
}

func ago(now time.Time, ms int64) string {
	d := now.Sub(time.UnixMilli(ms))
	switch {
	case d < time.Minute:
		return fmt.Sprintf("%ds", int(d.Seconds()))
	case d < time.Hour:
		return fmt.Sprintf("%dm", int(d.Minutes()))
	case d < 48*time.Hour:
		return fmt.Sprintf("%dh%02dm", int(d.Hours()), int(d.Minutes())%60)
	default:
		return fmt.Sprintf("%dd", int(d.Hours()/24))
	}
}

func humanBytes(b uint64) string {
	const k = 1024
	switch {
	case b >= k*k*k:
		return fmt.Sprintf("%.1f GiB", float64(b)/(k*k*k))
	case b >= k*k:
		return fmt.Sprintf("%.1f MiB", float64(b)/(k*k))
	case b >= k:
		return fmt.Sprintf("%.1f KiB", float64(b)/k)
	default:
		return fmt.Sprintf("%d B", b)
	}
}
