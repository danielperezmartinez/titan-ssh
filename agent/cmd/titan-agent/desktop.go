package main

import (
	"bufio"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/inject"
)

// The desktop helper (ADR-0016 §3) is `titan-agent --desktop` running on the
// user's interactive desktop, where input can be injected; the daemon and
// the fronts run where sshd puts them (session 0 on Windows), where it
// cannot. A --input front reaches the helper through its own rendezvous in
// the state dir: desktop.lock keeps it to one per user, and desktop.json says
// where it listens on 127.0.0.1 and with which token. The helper never exits
// on its own: it ends with the user's desktop session, or when the user
// removes it (--remove-desktop).
const (
	desktopLockName  = "desktop.lock"
	desktopStateName = "desktop.json"
	// desktopErrorName holds why the last helper could not start, for the
	// front to report: the helper has no stdio to say it.
	desktopErrorName = "desktop-error.txt"
)

// Desktop control requests, over a desktopControlMagic connection.
const (
	desktopOpStatus = "status"
	desktopOpStop   = "stop"
)

type desktopReply struct {
	Error   string `json:"error,omitempty"`
	Agent   string `json:"agent,omitempty"`
	PID     int    `json:"pid,omitempty"`
	Session int    `json:"session,omitempty"`
}

// runDesktop is the --desktop mode. It returns nil at once, touching nothing,
// when a helper already runs for this user.
func runDesktop(stateDir string) error {
	if err := ensureStateDir(stateDir); err != nil {
		return withCode(codeStateDir, err)
	}
	inj, err := inject.New()
	if err != nil {
		code := codeNoDesktop
		if errors.Is(err, inject.ErrUnsupported) {
			code = codeInputUnsupported
		}
		return recordDesktopError(stateDir, withCode(code, err))
	}
	h, err := startDesktop(stateDir, inj, desktopSession())
	if errors.Is(err, errLocked) {
		return nil
	}
	if err != nil {
		return recordDesktopError(stateDir, err)
	}
	_ = os.Remove(filepath.Join(stateDir, desktopErrorName))
	defer h.close()
	quit := make(chan struct{})
	defer close(quit)
	go h.maintain(quit)
	h.serve()
	return nil
}

// recordDesktopError leaves err where the front looks for it, and returns it.
func recordDesktopError(stateDir string, err error) error {
	_ = os.WriteFile(filepath.Join(stateDir, desktopErrorName), []byte(err.Error()), 0o600)
	return err
}

// desktopHelper is a started helper: it holds desktop.lock, listens on
// loopback and has published desktop.json.
type desktopHelper struct {
	dir      string
	lock     *os.File
	ln       net.Listener
	token    []byte
	record   agentState
	inj      inject.Injector
	injMu    sync.Mutex // one injector for every connection
	runLogMu sync.Mutex // one writer of desktop-run.log
	stopping atomic.Bool
	conns    sync.WaitGroup
	slots    handshakeSlots
}

func startDesktop(dir string, inj inject.Injector, session int) (*desktopHelper, error) {
	lock, err := lockNamed(dir, desktopLockName)
	if errors.Is(err, errLocked) {
		return nil, err
	}
	if err != nil {
		return nil, withCode(codeLock, err)
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		lock.Close()
		return nil, err
	}
	token := make([]byte, tokenLen)
	if _, err := rand.Read(token); err != nil {
		ln.Close()
		lock.Close()
		return nil, err
	}
	st := agentState{
		Schema:  stateSchema,
		Agent:   version,
		Port:    ln.Addr().(*net.TCPAddr).Port,
		Token:   hex.EncodeToString(token),
		PID:     os.Getpid(),
		Session: session,
	}
	if err := writeStateFile(dir, desktopStateName, st); err != nil {
		ln.Close()
		lock.Close()
		return nil, err
	}
	return &desktopHelper{dir: dir, lock: lock, ln: ln, token: token, record: st, inj: inj, slots: newHandshakeSlots()}, nil
}

// maintain puts desktop.json back if it goes missing or is overwritten, like
// the daemon does with agent.json.
func (h *desktopHelper) maintain(quit <-chan struct{}) {
	t := time.NewTicker(maintainInterval)
	defer t.Stop()
	for {
		select {
		case <-quit:
			return
		case <-t.C:
			if st, err := readStateFile(h.dir, desktopStateName); err != nil || st != h.record {
				_ = writeStateFile(h.dir, desktopStateName, h.record)
			}
		}
	}
}

// serve accepts connections until a stop request closes the listener. An
// input connection lives as long as its client: a mouse pad may sit idle for
// hours. As in the daemon, a connection that arrives while maxHandshakes
// others are still in their handshake is closed at once.
func (h *desktopHelper) serve() {
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
		h.conns.Add(1)
		go func() {
			defer h.conns.Done()
			defer conn.Close()
			// A run connection has no legacy variant (ADR-0019 §2).
			magic, err := acceptMagics(conn, h.token,
				[]string{desktopMagic, desktopControlMagic, desktopRunMagic},
				map[string]string{
					legacyDesktopMagic:        legacyDesktopAck,
					legacyDesktopControlMagic: legacyDesktopControlAck,
				})
			h.slots.release()
			if err != nil {
				return
			}
			switch magic {
			case desktopControlMagic, legacyDesktopControlMagic:
				h.serveControl(conn)
				return
			case desktopRunMagic:
				h.serveRun(conn)
				return
			}
			_ = inject.Serve(conn, h.inj, &h.injMu)
		}()
	}
}

func (h *desktopHelper) serveControl(conn net.Conn) {
	_ = conn.SetDeadline(time.Now().Add(controlTimeout))
	line, err := bufio.NewReaderSize(io.LimitReader(conn, controlLineMax), controlLineMax).ReadBytes('\n')
	if err != nil {
		return
	}
	var req controlRequest
	if err := json.Unmarshal(line, &req); err != nil {
		writeDesktopReply(conn, desktopReply{Error: "malformed request"})
		return
	}
	switch req.Op {
	case desktopOpStatus:
		writeDesktopReply(conn, desktopReply{Agent: version, PID: os.Getpid(), Session: h.record.Session})
	case desktopOpStop:
		writeDesktopReply(conn, desktopReply{})
		h.stopping.Store(true)
		h.ln.Close()
	default:
		writeDesktopReply(conn, desktopReply{Error: "unknown op " + req.Op})
	}
}

func writeDesktopReply(w io.Writer, r desktopReply) {
	data, _ := json.Marshal(r)
	_, _ = w.Write(append(data, '\n'))
}

// close drops desktop.json if it is still this helper's and releases the lock.
func (h *desktopHelper) close() {
	h.ln.Close()
	removeOwnStateFile(h.dir, desktopStateName, os.Getpid())
	h.lock.Close()
}

// desktopRequest sends one control request to the helper st describes.
func desktopRequest(st agentState, op string) (desktopReply, error) {
	conn, err := dialWith(st, desktopControlMagic)
	if err != nil {
		return desktopReply{}, err
	}
	defer conn.Close()
	return desktopExchange(conn, op)
}

// stopLegacyDesktop stops a helper of an older version, which answers only
// the first handshake (rendezvous.go), while it holds its lock.
func stopLegacyDesktop(stateDir string, st agentState) error {
	if !legacyAllowed(stateDir, desktopLockName) {
		return nil
	}
	conn, err := dialLegacy(st, legacyDesktopControlMagic, legacyDesktopControlAck)
	if err != nil {
		return err
	}
	defer conn.Close()
	_, err = desktopExchange(conn, desktopOpStop)
	return err
}

// desktopExchange sends op on an authenticated desktop control connection
// and reads the reply.
func desktopExchange(conn net.Conn, op string) (desktopReply, error) {
	var reply desktopReply
	_ = conn.SetDeadline(time.Now().Add(controlTimeout))
	data, _ := json.Marshal(controlRequest{Op: op})
	if _, err := conn.Write(append(data, '\n')); err != nil {
		return reply, err
	}
	line, err := bufio.NewReader(conn).ReadBytes('\n')
	if err != nil {
		return reply, fmt.Errorf("no reply from the desktop helper: %w", err)
	}
	if err := json.Unmarshal(line, &reply); err != nil {
		return reply, fmt.Errorf("unreadable reply from the desktop helper: %w", err)
	}
	if reply.Error != "" {
		return reply, errors.New(reply.Error)
	}
	return reply, nil
}

// The scheduled task, through vars so tests never touch the real one.
var (
	taskExists = desktopTaskExists
	taskDelete = deleteDesktopTask
)

// How a --input front waits for the helper it launched. Vars so tests can
// shorten them. Starting a scheduled task can take a few seconds.
var (
	desktopWait  = 15 * time.Second
	relaunchGap  = 3 * time.Second
	desktopRetry = 100 * time.Millisecond
)

// connectDesktop returns an input connection to the user's desktop helper. While
// none answers, it calls launch whenever the lock shows no helper is alive. A
// helper of another agent version is stopped and replaced, so an upgrade
// takes effect on the next connection. Errors carry their contract code.
func connectDesktop(stateDir string, launch func() error) (net.Conn, error) {
	return connectDesktopWith(stateDir, desktopMagic, launch)
}

// connectDesktopWith is connectDesktop for a connection of the kind magic.
func connectDesktopWith(stateDir, magic string, launch func() error) (net.Conn, error) {
	deadline := time.Now().Add(desktopWait)
	var launchedAt time.Time
	for {
		if st, err := readStateFile(stateDir, desktopStateName); err == nil {
			conn, err := dialWith(st, magic)
			switch {
			case err == nil && st.Agent == version:
				return conn, nil
			case err == nil:
				conn.Close()
				if _, err := desktopRequest(st, desktopOpStop); err == nil {
					_ = waitNamedLockFree(stateDir, desktopLockName, 5*time.Second)
				}
			case errors.Is(err, errRejected) && st.Agent != version:
				// A helper of an older version, which may not know this kind
				// of connection or even this handshake: replace it as well.
				_, err := desktopRequest(st, desktopOpStop)
				if errors.Is(err, errRejected) {
					err = stopLegacyDesktop(stateDir, st)
				}
				if err == nil {
					_ = waitNamedLockFree(stateDir, desktopLockName, 5*time.Second)
				}
			}
		}
		if time.Since(launchedAt) >= relaunchGap {
			held, err := lockNamedHeld(stateDir, desktopLockName)
			if err != nil {
				return nil, withCode(codeLock, err)
			}
			if !held {
				if err := launch(); err != nil {
					return nil, err
				}
				launchedAt = time.Now()
			}
		}
		if time.Now().After(deadline) {
			return nil, desktopGiveUp(stateDir, launchedAt)
		}
		time.Sleep(desktopRetry)
	}
}

// desktopGiveUp explains why no helper answered: what the helper itself wrote
// if it failed after the launch, or, most often, that nobody is signed in to
// the desktop (Windows does not run the task then).
func desktopGiveUp(stateDir string, launchedAt time.Time) error {
	path := filepath.Join(stateDir, desktopErrorName)
	if fi, err := os.Stat(path); err == nil && !launchedAt.IsZero() && !fi.ModTime().Before(launchedAt.Add(-time.Second)) {
		if data, err := os.ReadFile(path); err == nil && len(data) > 0 {
			return withCode(codeNoDesktop, fmt.Errorf("the desktop helper failed: %s", data))
		}
	}
	return withCode(codeNoDesktop, fmt.Errorf(
		"the desktop helper did not start within %v: nobody seems to be signed in to the desktop", desktopWait))
}

func waitNamedLockFree(stateDir, name string, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	for {
		held, err := lockNamedHeld(stateDir, name)
		if err != nil || !held {
			return err
		}
		if time.Now().After(deadline) {
			return errors.New("the desktop helper did not exit in time")
		}
		time.Sleep(20 * time.Millisecond)
	}
}

// runInput is the --input mode: it connects the SSH exec channel to the
// desktop helper, launching it first if needed, and splices the two until
// either side closes. The helper releases whatever was left pressed.
func runInput(stateDir string) error {
	if err := ensureStateDir(stateDir); err != nil {
		return withCode(codeStateDir, err)
	}
	conn, err := connectDesktop(stateDir, func() error { return launchDesktop(stateDir) })
	if err != nil {
		return err
	}
	defer conn.Close()
	splice(conn)
	return nil
}

// desktopReport is the "desktop" part of --status --json, on systems with a
// desktop helper (Windows).
type desktopReport struct {
	// Task is whether the scheduled task that starts the helper is registered.
	Task    bool   `json:"task"`
	State   string `json:"state"` // stateRunning, stateStopped or stateUnreachable
	Agent   string `json:"agent,omitempty"`
	PID     int    `json:"pid,omitempty"`
	Session int    `json:"session,omitempty"`
	// Runs are the last programs started with --desktop-run, newest first.
	Runs []desktopRun `json:"runs,omitempty"`
}

// queryDesktop reports the helper as it is now.
func queryDesktop(stateDir string) desktopReport {
	r := desktopReport{Task: taskExists(stateDir), State: stateStopped, Runs: recentRuns(stateDir, desktopRunsReported)}
	st, err := readStateFile(stateDir, desktopStateName)
	if err == nil {
		if reply, err := desktopRequest(st, desktopOpStatus); err == nil {
			r.State, r.Agent, r.PID, r.Session = stateRunning, reply.Agent, reply.PID, reply.Session
			return r
		}
	}
	if held, _ := lockNamedHeld(stateDir, desktopLockName); held {
		r.State = stateUnreachable
		if err == nil {
			r.Agent, r.PID, r.Session = st.Agent, st.PID, st.Session
		}
	}
	return r
}

// removeDesktop is --remove-desktop: it stops the helper, deletes its task,
// its executable copies and the record of its launches. Programs it started
// keep running. Nothing to remove is not an error.
func removeDesktop(stateDir string) error {
	if err := ensureStateDir(stateDir); err != nil {
		return withCode(codeStateDir, err)
	}
	if st, err := readStateFile(stateDir, desktopStateName); err == nil {
		_, err := desktopRequest(st, desktopOpStop)
		if errors.Is(err, errRejected) {
			err = stopLegacyDesktop(stateDir, st)
		}
		if err == nil {
			if err := waitNamedLockFree(stateDir, desktopLockName, 5*time.Second); err != nil {
				return err
			}
		}
	}
	if held, err := lockNamedHeld(stateDir, desktopLockName); err == nil && held {
		return errors.New("the desktop helper holds its lock but does not answer")
	}
	if err := taskDelete(stateDir); err != nil {
		return err
	}
	_ = removeIfExists(filepath.Join(stateDir, desktopStateName))
	_ = removeIfExists(filepath.Join(stateDir, desktopErrorName))
	_ = removeIfExists(filepath.Join(stateDir, desktopRunLogName))
	removeDesktopCopies(stateDir, "")
	return nil
}

// desktopCopyName is the helper's executable copy for this agent version
// (ADR-0017).
func desktopCopyName() string { return "desktop-" + version + ".exe" }

// removeDesktopCopies deletes every helper executable copy in stateDir but
// keep. One that is running cannot be deleted on Windows and stays for later.
func removeDesktopCopies(stateDir, keep string) {
	matches, _ := filepath.Glob(filepath.Join(stateDir, "desktop-*.exe"))
	for _, m := range matches {
		if filepath.Base(m) != keep {
			_ = os.Remove(m)
		}
	}
}
