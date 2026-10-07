package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// --desktop-run (ADR-0019) asks the desktop helper, which runs on the user's
// interactive desktop, to start a program there: a shell under sshd (session
// 0 on Windows) cannot open a window the user sees. It goes over its own
// connection kind, desktopRunMagic, which only the mutual handshake accepts.
// The helper starts the program with its own unelevated token and never
// through a shell, and records every launch in desktop-run.log.

// agentPathEnv names the agent's own executable in the environment of every
// session shell, so a shell can find the agent to run --desktop-run.
const agentPathEnv = "TITAN_AGENT"

const (
	desktopRunLogName = "desktop-run.log"
	// runLineMax bounds a run request. A Windows command line holds at most
	// 32767 UTF-16 units, which JSON escaping can more than double.
	runLineMax = 128 * 1024
	// desktopRunKeep and desktopRunTrim bound the log: past desktopRunTrim
	// records, it is cut back to the last desktopRunKeep.
	desktopRunKeep = 100
	desktopRunTrim = 200
	// desktopRunsReported is how many of the last launches --status reports.
	desktopRunsReported = 10
)

type runRequest struct {
	Program string   `json:"program"`
	Args    []string `json:"args,omitempty"`
	Dir     string   `json:"dir,omitempty"`
}

type runReply struct {
	Error string `json:"error,omitempty"`
	PID   int    `json:"pid,omitempty"`
}

// desktopRun is one launch as desktop-run.log records it and --status
// reports it.
type desktopRun struct {
	TimeMs  int64    `json:"timeMs"`
	Program string   `json:"program"`
	Args    []string `json:"args,omitempty"`
	Dir     string   `json:"dir,omitempty"`
	PID     int      `json:"pid,omitempty"`
	Error   string   `json:"error,omitempty"`
}

// launchProgram starts a validated request on this desktop and returns the
// new process's PID. A var so tests never start real programs.
var launchProgram = startProgram

// validateRun checks a request before anything is started: an absolute
// program that exists and is not a batch file (which Windows would hand to
// cmd.exe), and an absolute working directory that exists, if one is given.
func validateRun(req runRequest) error {
	for _, s := range append([]string{req.Program, req.Dir}, req.Args...) {
		if strings.ContainsRune(s, 0) {
			return errors.New("the request contains a NUL character")
		}
	}
	if req.Program == "" {
		return errors.New("no program to run")
	}
	if !filepath.IsAbs(req.Program) {
		return fmt.Errorf("the program %q is not an absolute path", req.Program)
	}
	// Windows ignores trailing dots and spaces in a file name, and a colon
	// past the volume names an alternate data stream: neither may hide what
	// the extension is.
	if strings.Contains(req.Program[len(filepath.VolumeName(req.Program)):], ":") {
		return fmt.Errorf("the program %s names a data stream", req.Program)
	}
	switch strings.ToLower(filepath.Ext(strings.TrimRight(req.Program, ". "))) {
	case ".bat", ".cmd":
		return fmt.Errorf("%s is a batch file: run cmd.exe /c with it instead", req.Program)
	}
	if fi, err := os.Stat(req.Program); err != nil {
		return fmt.Errorf("the program %s cannot be found: %w", req.Program, err)
	} else if fi.IsDir() {
		return fmt.Errorf("the program %s is a directory", req.Program)
	}
	if req.Dir != "" {
		if !filepath.IsAbs(req.Dir) {
			return fmt.Errorf("the working directory %q is not an absolute path", req.Dir)
		}
		if fi, err := os.Stat(req.Dir); err != nil || !fi.IsDir() {
			return fmt.Errorf("the working directory %s does not exist", req.Dir)
		}
	}
	return nil
}

// serveRun handles one run connection on the helper: one request line, one
// reply line. Every request that parses is recorded, started or not.
func (h *desktopHelper) serveRun(conn net.Conn) {
	_ = conn.SetDeadline(time.Now().Add(controlTimeout))
	line, err := bufio.NewReaderSize(io.LimitReader(conn, runLineMax), 4096).ReadBytes('\n')
	if err != nil {
		writeRunReply(conn, runReply{Error: "the request is missing or too long"})
		return
	}
	var req runRequest
	if err := json.Unmarshal(line, &req); err != nil {
		writeRunReply(conn, runReply{Error: "malformed request"})
		return
	}
	var reply runReply
	if err := validateRun(req); err != nil {
		reply.Error = err.Error()
	} else if pid, err := launchProgram(req); err != nil {
		reply.Error = err.Error()
	} else {
		reply.PID = pid
	}
	h.recordRun(desktopRun{
		TimeMs: time.Now().UnixMilli(), Program: clip(req.Program), Args: clipArgs(req.Args), Dir: clip(req.Dir),
		PID: reply.PID, Error: clip(reply.Error),
	})
	writeRunReply(conn, reply)
}

// What a record keeps of a request, so that the log and --status stay small
// whatever the command line.
const (
	recordFieldMax = 512
	recordArgsMax  = 32
)

func clip(s string) string {
	if len(s) <= recordFieldMax {
		return s
	}
	return strings.ToValidUTF8(s[:recordFieldMax], "") + "…"
}

func clipArgs(args []string) []string {
	var out []string
	for i, a := range args {
		if i == recordArgsMax {
			out = append(out, fmt.Sprintf("… (%d more)", len(args)-i))
			break
		}
		out = append(out, clip(a))
	}
	return out
}

func writeRunReply(w io.Writer, r runReply) {
	data, _ := json.Marshal(r)
	_, _ = w.Write(append(data, '\n'))
}

// recordRun appends run to desktop-run.log, cutting it back to its last
// records once it grows past desktopRunTrim. A failure to record does not
// undo the launch.
func (h *desktopHelper) recordRun(run desktopRun) {
	h.runLogMu.Lock()
	defer h.runLogMu.Unlock()
	data, err := json.Marshal(run)
	if err != nil {
		return
	}
	lines := readRunLines(h.dir)
	lines = append(lines, data)
	if len(lines) > desktopRunTrim {
		lines = lines[len(lines)-desktopRunKeep:]
	}
	_ = writePrivateFile(h.dir, desktopRunLogName, append(bytes.Join(lines, []byte{'\n'}), '\n'))
}

// readRunLines returns the log's non-empty lines, oldest first.
func readRunLines(dir string) [][]byte {
	data, err := os.ReadFile(filepath.Join(dir, desktopRunLogName))
	if err != nil {
		return nil
	}
	var lines [][]byte
	for line := range bytes.SplitSeq(data, []byte{'\n'}) {
		if line = bytes.TrimSpace(line); len(line) > 0 {
			lines = append(lines, line)
		}
	}
	return lines
}

// recentRuns returns up to n of the last launches, newest first. Records that
// do not parse are skipped.
func recentRuns(dir string, n int) []desktopRun {
	lines := readRunLines(dir)
	var runs []desktopRun
	for i := len(lines) - 1; i >= 0 && len(runs) < n; i-- {
		var r desktopRun
		if json.Unmarshal(lines[i], &r) == nil {
			runs = append(runs, r)
		}
	}
	return runs
}

// writePrivateFile replaces dir/name with data through a private temporary
// file, so a reader never sees a torn file.
func writePrivateFile(dir, name string, data []byte) error {
	tmp, err := createPrivateTemp(dir, ".private-*.tmp")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name()) // no-op once renamed
	_, werr := tmp.Write(data)
	if cerr := tmp.Close(); werr == nil {
		werr = cerr
	}
	if werr != nil {
		return werr
	}
	return renameRetrying(tmp.Name(), filepath.Join(dir, name))
}

// runDesktopProgram is the --desktop-run mode: it resolves the program as the
// calling shell would, finds or launches the desktop helper as --input does,
// and asks it to start the program in dir (the caller's working directory if
// empty). It prints the new process's PID.
func runDesktopProgram(stateDir, dir string, argv []string) error {
	if len(argv) == 0 {
		return errors.New("--desktop-run needs a program to run")
	}
	req, err := resolveRun(dir, argv)
	if err != nil {
		return err
	}
	if !desktopSupported {
		return withCode(codeInputUnsupported, errors.New("--desktop-run only supports Windows destinations for now"))
	}
	if err := ensureStateDir(stateDir); err != nil {
		return withCode(codeStateDir, err)
	}
	conn, err := connectDesktopWith(stateDir, desktopRunMagic, func() error { return launchDesktop(stateDir) })
	if err != nil {
		return err
	}
	defer conn.Close()
	reply, err := runExchange(conn, req)
	if err != nil {
		return err
	}
	fmt.Println(reply.PID)
	return nil
}

// resolveRun turns the command line into a request: the program found as the
// calling shell would find it (on its PATH, never in the current directory by
// accident), and both paths absolute.
func resolveRun(dir string, argv []string) (runRequest, error) {
	program, err := exec.LookPath(argv[0])
	if err != nil {
		return runRequest{}, fmt.Errorf("the program %s cannot be found: %w", argv[0], err)
	}
	if program, err = filepath.Abs(program); err != nil {
		return runRequest{}, err
	}
	if dir == "" {
		if dir, err = os.Getwd(); err != nil {
			return runRequest{}, err
		}
	}
	if dir, err = filepath.Abs(dir); err != nil {
		return runRequest{}, err
	}
	return runRequest{Program: program, Args: argv[1:], Dir: dir}, nil
}

// runExchange sends req on an authenticated run connection and reads the
// helper's reply.
func runExchange(conn net.Conn, req runRequest) (runReply, error) {
	var reply runReply
	_ = conn.SetDeadline(time.Now().Add(controlTimeout))
	data, err := json.Marshal(req)
	if err != nil {
		return reply, err
	}
	if len(data)+1 > runLineMax {
		return reply, errors.New("the command line is too long")
	}
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
