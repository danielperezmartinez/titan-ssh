package main

import (
	"errors"
	"fmt"
	"os"
	"strings"
)

// Updating the agent without closing its sessions (ADR-0022): the client
// installs the new version and runs it with --restart, which ends the running
// daemon and starts this one in its place. The sessions stay in their hosts,
// which the new daemon finds and routes to; a client attached at that moment
// sees a dropped connection and reattaches, as after a network cut.

// restartDaemon is --restart. A daemon from before the session hosts keeps
// its sessions inside itself and does not know the request: that is
// E_RESTART_UNSUPPORTED, and only --stop, which closes them, replaces it.
func restartDaemon(stateDir string) error {
	if err := ensureStateDir(stateDir); err != nil {
		return withCode(codeStateDir, err)
	}
	_, st, err := request(stateDir, controlRequest{Op: opRestart})
	switch {
	case err == nil:
		if err := waitLockFree(stateDir, stopWait); err != nil {
			return err
		}
	case strings.HasPrefix(err.Error(), "unknown op"):
		return withCode(codeRestartUnsupported, fmt.Errorf(
			"titan-agent %s (PID %d) holds its sessions inside the daemon and cannot hand them over: --stop replaces it, closing them",
			st.Agent, st.PID))
	default:
		held, lerr := lockHeld(stateDir)
		if lerr != nil {
			return withCode(codeLock, lerr)
		}
		if held {
			return fmt.Errorf("a daemon (titan-agent %s, PID %d by its last record) holds the lock but does not answer: end that process by hand, or restart the machine",
				st.Agent, st.PID)
		}
		if !hasLiveHosts(stateDir) {
			return nil // nothing runs: the next session starts this version
		}
	}
	conn, err := dialOrSpawn(stateDir, func() error { return spawnDaemonFunc(stateDir) })
	if err != nil {
		return err
	}
	return conn.Close()
}

// spawnDaemonFunc launches a daemon for --restart and the control commands. A
// var so tests can run one in process.
var spawnDaemonFunc = spawnDaemon

// hasLiveHosts reports whether any session host is alive in stateDir.
func hasLiveHosts(stateDir string) bool {
	for _, rec := range hostRecords(stateDir) {
		if hostAlive(stateDir, rec.ID) {
			return true
		}
	}
	return false
}

// requestStarting is request for the control commands: when no daemon runs
// but session hosts do (the daemon was killed, or a --restart was cut short),
// it starts one first, so the sessions are seen and can be closed.
func requestStarting(stateDir string, req controlRequest) (controlReply, agentState, error) {
	reply, st, err := request(stateDir, req)
	if err == nil {
		return reply, st, nil
	}
	if held, lerr := lockHeld(stateDir); lerr != nil || held || !hasLiveHosts(stateDir) {
		return reply, st, err
	}
	conn, derr := dialOrSpawn(stateDir, func() error { return spawnDaemonFunc(stateDir) })
	if derr != nil {
		return reply, st, err
	}
	conn.Close()
	return request(stateDir, req)
}

// removeUnused is --remove-unused: it deletes each of paths, the binaries of
// other versions the client no longer needs, unless a live daemon or session
// host runs from it. On Unix a running binary can be deleted, but a session
// started by it needs it again for --status and --desktop-run (through
// TITAN_AGENT), and a daemon to start the hosts of new sessions. A path that
// does not exist is not an error.
func removeUnused(stateDir string, paths []string) error {
	if err := ensureStateDir(stateDir); err != nil {
		return withCode(codeStateDir, err)
	}
	var inUse []os.FileInfo
	addInUse := func(exe string) {
		if exe == "" {
			return
		}
		if fi, err := os.Stat(exe); err == nil {
			inUse = append(inUse, fi)
		}
	}
	if held, _ := lockHeld(stateDir); held {
		if st, err := readState(stateDir); err == nil {
			addInUse(st.Exe)
		}
	}
	for _, rec := range hostRecords(stateDir) {
		if hostAlive(stateDir, rec.ID) {
			addInUse(rec.Exe)
		}
	}
	var errs []error
	for _, p := range paths {
		fi, err := os.Stat(p)
		if errors.Is(err, os.ErrNotExist) {
			continue
		}
		if err != nil {
			errs = append(errs, err)
			continue
		}
		used := false
		for _, u := range inUse {
			if os.SameFile(fi, u) {
				used = true
				break
			}
		}
		if used {
			fmt.Printf("titan-agent: kept %s: a running daemon or session uses it\n", p)
			continue
		}
		if err := os.Remove(p); err != nil && !errors.Is(err, os.ErrNotExist) {
			errs = append(errs, err)
		}
	}
	return errors.Join(errs...)
}
