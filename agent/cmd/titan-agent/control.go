package main

import (
	"bufio"
	"cmp"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net"
	"os"
	"runtime"
	"slices"
	"strconv"
	"strings"
	"sync"
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
	opStatus  = "status"
	opClose   = "close"
	opStop    = "stop"
	opPreview = "preview"
	opRename  = "rename"
	// opRestart ends the daemon but leaves every session running in its host
	// (ADR-0022), for the next daemon to route to: how the agent is updated.
	opRestart = "restart"
)

type controlRequest struct {
	Op      string `json:"op"`
	Session string `json:"session,omitempty"`
	// Name is opRename's new name; empty takes the name away.
	Name string `json:"name,omitempty"`
}

type controlReply struct {
	Error   string         `json:"error,omitempty"`
	Status  *statusReport  `json:"status,omitempty"`
	Closed  bool           `json:"closed,omitempty"`  // opClose: the session existed
	Renamed bool           `json:"renamed,omitempty"` // opRename: the session existed
	Preview *previewReport `json:"preview,omitempty"`
}

// previewReport is the --preview --json contract: the end of a session's
// history and the terminal size it was drawn for, so the client can render
// what the terminal shows without attaching to it.
type previewReport struct {
	Cols uint16 `json:"cols"`
	Rows uint16 `json:"rows"`
	Data []byte `json:"data"` // base64 in JSON
}

// previewBytes is how much history a preview carries: enough for a full
// screen of a shell, small enough to fetch over a slow link.
const previewBytes = 16 * 1024

// errNoSession reports a preview of a session the daemon does not hold.
var errNoSession = errors.New("no such session")

// Daemon states in a statusReport.
const (
	stateRunning     = "running"     // a daemon answered with its status
	stateStopped     = "stopped"     // no daemon runs for this user
	stateUnreachable = "unreachable" // a daemon holds the lock but does not answer
)

// statusReport is the --status --json contract the client parses (schema 1).
// Times are Unix milliseconds on the destination's clock; the client compares
// them with nowMs, never with its own clock. Fields are only ever added, as
// optional ones, so an older client still reads a newer agent.
type statusReport struct {
	Schema int    `json:"schema"`
	State  string `json:"state"`
	// The version of the daemon: from its reply, or from agent.json for one
	// that does not answer. CLI is this binary's version.
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
	// Desktop is the mouse pad's desktop helper, on systems that have one
	// (Windows); the CLI adds it, whatever state the daemon is in.
	Desktop *desktopReport `json:"desktop,omitempty"`
	// Screens are the remote desktop sessions (--screen, ADR-0029) running
	// now; the CLI adds them, whatever state the daemon is in.
	Screens []screenViewer `json:"screens,omitempty"`
	// RemoteDesktop is the remote desktop's consent on this destination
	// (ADR-0031 §3), for the canary's agent with a graphical session.
	RemoteDesktop *remoteDesktopReport `json:"remoteDesktop,omitempty"`
	// SessionHosts says the daemon keeps each session in its own host process
	// (ADR-0022), so --restart updates it without closing any session.
	SessionHosts bool `json:"sessionHosts,omitempty"`
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
	// CPUPercent is the CPU the shell and its descendants used over the
	// status sample (100 = one core); absent when not measurable.
	CPUPercent *int `json:"cpuPercent,omitempty"`

	Shell    string `json:"shell,omitempty"` // the program the session started
	ShellPID int    `json:"shellPid,omitempty"`
	// Foreground is the program running in front of the user, absent while
	// the shell itself waits at its prompt. On POSIX it is the terminal's
	// foreground process group; on Windows, a guess from the process tree
	// (the newest descendant of the shell).
	Foreground    string `json:"foreground,omitempty"`
	ForegroundPID int    `json:"foregroundPid,omitempty"`
	Cwd           string `json:"cwd,omitempty"` // the shell's directory; Linux and Windows
	Cols          uint16 `json:"cols,omitempty"`
	Rows          uint16 `json:"rows,omitempty"`
	LastOutputMs  int64  `json:"lastOutputMs,omitempty"`
	Title         string `json:"title,omitempty"` // the last OSC 0/2 window title
	// Name is the one the user gave the session (--rename-session); absent if none.
	Name string `json:"name,omitempty"`
	// Agent is the version of the session host that holds the session (ADR-0022):
	// an older one keeps its sessions across an update of the daemon.
	Agent string `json:"agent,omitempty"`
}

const statusSchema = 1

// cpuSample is how long a status request watches the sessions to tell how
// much CPU they use now.
var cpuSample = 250 * time.Millisecond

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
	// The request arrived; answering may take a status sample.
	_ = conn.SetDeadline(time.Now().Add(controlTimeout))
	switch req.Op {
	case opStatus:
		st := d.status()
		writeReply(conn, controlReply{Status: &st})
	case opClose:
		writeReply(conn, controlReply{Closed: d.hosts.close(req.Session)})
	case opRename:
		writeReply(conn, controlReply{Renamed: d.hosts.rename(req.Session, req.Name)})
	case opStop:
		writeReply(conn, controlReply{})
		d.stop(true)
	case opRestart:
		writeReply(conn, controlReply{})
		d.stop(false)
	case opPreview:
		rec, ok := d.hosts.lookup(req.Session)
		if !ok {
			writeReply(conn, controlReply{Error: errNoSession.Error()})
			return
		}
		reply, err := hostRequestTo(rec, hostRequest{Op: hostOpTail, Bytes: previewBytes})
		if err != nil || reply.Tail == nil {
			writeReply(conn, controlReply{Error: errNoSession.Error()})
			return
		}
		writeReply(conn, controlReply{Preview: reply.Tail})
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
		Schema:       statusSchema,
		State:        stateRunning,
		Agent:        version,
		CLI:          version,
		PID:          os.Getpid(),
		OS:           runtime.GOOS,
		Arch:         runtime.GOARCH,
		NowMs:        now.UnixMilli(),
		StartedMs:    d.started.UnixMilli(),
		Sessions:     []sessionReport{},
		SessionHosts: true,
	}
	// Two snapshots a moment apart: the CPU time used between them is the
	// load now. Either is nil when the system cannot be measured.
	before, _ := procmem.Snapshot()
	start := time.Now()
	if before != nil {
		time.Sleep(cpuSample)
	}
	table, _ := procmem.Snapshot()
	sample := sampleWindow{before: before, after: table, elapsed: time.Since(start)}
	infos := d.hostInfos()
	if table != nil {
		// The daemon and every session host, which may not be under it.
		pids := []int{os.Getpid()}
		for _, in := range infos {
			pids = append(pids, in.PID)
		}
		if b, ok := table.TreesBytes(pids...); ok {
			st.MemoryBytes = &b
		}
	}
	for _, in := range infos {
		r := sessionFrom(in.sessionInfo(), sample)
		r.Agent = in.Agent
		st.Sessions = append(st.Sessions, r)
	}
	return st
}

// hostInfos asks every session host about its session, oldest session first.
// A host that does not answer is left out.
func (d *daemon) hostInfos() []hostInfo {
	hosts := d.hosts.all()
	infos := make([]*hostInfo, len(hosts))
	var wg sync.WaitGroup
	for i, rec := range hosts {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if reply, err := hostRequestTo(rec, hostRequest{Op: hostOpInfo}); err == nil && reply.Info != nil {
				infos[i] = reply.Info
			}
		}()
	}
	wg.Wait()
	out := make([]hostInfo, 0, len(infos))
	for _, in := range infos {
		if in != nil {
			out = append(out, *in)
		}
	}
	slices.SortFunc(out, func(a, b hostInfo) int { return cmp.Compare(a.CreatedMs, b.CreatedMs) })
	return out
}

// sampleWindow is the process table at the start and end of a status sample.
type sampleWindow struct {
	before, after *procmem.Table
	elapsed       time.Duration
}

// cpuPercent is the CPU pid's tree used during the sample, 100 per core.
func (w sampleWindow) cpuPercent(pid int) (int, bool) {
	if w.before == nil || w.after == nil || w.elapsed <= 0 {
		return 0, false
	}
	t0, ok0 := w.before.TreeCPU(pid)
	t1, ok1 := w.after.TreeCPU(pid)
	if !ok0 || !ok1 {
		return 0, false
	}
	// A process that exits takes its CPU time with it: never negative.
	used := max(t1-t0, 0)
	return int((used*100 + w.elapsed/2) / w.elapsed), true
}

func sessionFrom(in session.Info, w sampleWindow) sessionReport {
	r := sessionReport{
		ID:          in.ID,
		CreatedMs:   in.Created.UnixMilli(),
		LastUsedMs:  in.LastUsed.UnixMilli(),
		Clients:     in.Clients,
		Closed:      in.Closed,
		BufferBytes: in.BufferBytes,
		Shell:       in.Shell,
		ShellPID:    in.ShellPID,
		Cols:        in.Cols,
		Rows:        in.Rows,
		Title:       in.Title,
		Name:        in.Name,
	}
	if !in.Detached.IsZero() {
		r.DetachedMs = in.Detached.UnixMilli()
	}
	if !in.LastOutput.IsZero() {
		r.LastOutputMs = in.LastOutput.UnixMilli()
	}
	table := w.after
	if table == nil || in.ShellPID <= 0 || in.Closed {
		return r
	}
	if b, ok := table.TreeBytes(in.ShellPID); ok {
		r.MemoryBytes = &b
	}
	if p, ok := w.cpuPercent(in.ShellPID); ok {
		r.CPUPercent = &p
	}
	fg := in.ForegroundPID
	if fg == 0 {
		fg = table.Newest(in.ShellPID)
	}
	if fg > 0 && fg != in.ShellPID {
		if name := table.Name(fg); name != "" {
			r.Foreground, r.ForegroundPID = name, fg
		}
	}
	// The shell's directory, not the running program's: that is where the
	// user is (a program may move elsewhere, as busybox top does to /proc).
	r.Cwd = table.ShellDir(in.ShellPID, in.Cwd)
	return r
}

// request sends one control request to the daemon published in stateDir. It
// returns fs.ErrNotExist when no state file exists, and the dial error when
// nobody answers (errRejected for a daemon that does not prove it holds the
// token, such as one of 0.1.0-beta.10 or older).
func request(stateDir string, req controlRequest) (controlReply, agentState, error) {
	var reply controlReply
	st, err := readState(stateDir)
	if err != nil {
		return reply, st, err
	}
	conn, err := dialControl(st)
	if err != nil {
		return reply, st, err
	}
	defer conn.Close()
	reply, err = exchange(conn, req)
	return reply, st, err
}

// exchange sends req on an authenticated control connection and reads the
// reply.
func exchange(conn net.Conn, req controlRequest) (controlReply, error) {
	var reply controlReply
	_ = conn.SetDeadline(time.Now().Add(controlTimeout))
	data, _ := json.Marshal(req)
	if _, err := conn.Write(append(data, '\n')); err != nil {
		return reply, err
	}
	line, err := bufio.NewReader(conn).ReadBytes('\n')
	if err != nil {
		return reply, fmt.Errorf("no reply from the daemon: %w", err)
	}
	if err := json.Unmarshal(line, &reply); err != nil {
		return reply, fmt.Errorf("unreadable reply from the daemon: %w", err)
	}
	if reply.Error != "" {
		return reply, errors.New(reply.Error)
	}
	return reply, nil
}

// controlTimeout bounds a control request once connected: a status snapshot
// walks the process table, which takes well under this.
var controlTimeout = 10 * time.Second

// queryStatus is --status: the daemon's own report when it answers, otherwise
// what can be told from the state dir (stopped or unreachable).
func queryStatus(stateDir string) (statusReport, error) {
	if err := ensureStateDir(stateDir); err != nil {
		return statusReport{}, withCode(codeStateDir, err)
	}
	st := statusReport{Schema: statusSchema, CLI: version, NowMs: time.Now().UnixMilli(), Sessions: []sessionReport{}}
	reply, rec, err := requestStarting(stateDir, controlRequest{Op: opStatus})
	if err == nil && reply.Status != nil {
		reply.Status.CLI = version
		return *reply.Status, nil
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
	reply, _, err := requestStarting(stateDir, controlRequest{Op: opClose, Session: id})
	if err == nil {
		return reply.Closed, nil
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

// renameSession is --rename-session: it gives one session the name the user
// chose ("" takes it away) and reports whether the session existed. The name
// arrives base64url-encoded (--name-base64), so it reaches the daemon intact
// whatever shell runs the command on the destination.
func renameSession(stateDir, id, nameBase64 string) (bool, error) {
	name, err := base64.RawURLEncoding.DecodeString(strings.TrimRight(nameBase64, "="))
	if err != nil {
		return false, fmt.Errorf("--name-base64 is not base64url: %w", err)
	}
	if err := ensureStateDir(stateDir); err != nil {
		return false, withCode(codeStateDir, err)
	}
	reply, _, err := requestStarting(stateDir, controlRequest{Op: opRename, Session: id, Name: string(name)})
	switch {
	case err == nil:
		return reply.Renamed, nil
	case errors.Is(err, fs.ErrNotExist):
		return false, nil // no daemon: there is no session to name
	}
	return false, err
}

// queryPreview is --preview: the end of one session's history, or
// errNoSession when the daemon does not hold it (or none runs).
func queryPreview(stateDir, id string) (previewReport, error) {
	if err := ensureStateDir(stateDir); err != nil {
		return previewReport{}, withCode(codeStateDir, err)
	}
	reply, _, err := requestStarting(stateDir, controlRequest{Op: opPreview, Session: id})
	switch {
	case err == nil && reply.Preview != nil:
		return *reply.Preview, nil
	case err == nil, errors.Is(err, fs.ErrNotExist):
		return previewReport{}, errNoSession
	case err.Error() == errNoSession.Error():
		return previewReport{}, errNoSession
	}
	return previewReport{}, err
}

// printStatus writes st for a person.
func printStatus(w io.Writer, st statusReport) {
	printDaemonStatus(w, st)
	if line := describeViewers(st.Screens); line != "" {
		fmt.Fprintln(w, line)
	}
	if d := st.Desktop; d != nil {
		switch {
		case d.State == stateRunning:
			fmt.Fprintf(w, "mouse pad desktop helper: running (PID %d, session %d)\n", d.PID, d.Session)
		case d.State == stateUnreachable:
			fmt.Fprintln(w, "mouse pad desktop helper: holds its lock but does not answer")
		case d.Task:
			fmt.Fprintln(w, "mouse pad desktop helper: not running (its scheduled task is registered)")
		}
		if d.Task || d.State != stateStopped {
			fmt.Fprintln(w, "remove it with --remove-desktop")
		}
	}
}

func printDaemonStatus(w io.Writer, st statusReport) {
	now := time.UnixMilli(st.NowMs)
	switch st.State {
	case stateStopped:
		fmt.Fprintln(w, "titan-agent: no daemon is running")
		return
	case stateUnreachable:
		fmt.Fprintln(w, "titan-agent: a daemon holds the lock but does not answer")
		if st.PID > 0 {
			fmt.Fprintf(w, "its last record says titan-agent %s, PID %d\n", st.Agent, st.PID)
		}
		fmt.Fprintln(w, "end that process by hand (or restart the machine); the next connection starts the current version")
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
		id := s.ID
		if s.Name != "" {
			id += " " + strconv.Quote(s.Name)
		}
		host := ""
		if s.Agent != "" && s.Agent != st.Agent {
			host = " · started by titan-agent " + s.Agent
		}
		fmt.Fprintf(w, "  %s  %s · created %s ago · memory %s · history %s%s\n",
			id, state, ago(now, s.CreatedMs), mem, humanBytes(s.BufferBytes), host)
		if s.Shell != "" {
			line := fmt.Sprintf("    shell %s (PID %d) · %dx%d", s.Shell, s.ShellPID, s.Cols, s.Rows)
			if s.Foreground != "" {
				line += " · running " + s.Foreground
			}
			if s.Cwd != "" {
				line += " · in " + s.Cwd
			}
			fmt.Fprintln(w, line)
		}
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
