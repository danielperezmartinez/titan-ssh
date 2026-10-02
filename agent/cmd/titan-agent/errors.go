package main

import "strings"

// Error-contract codes the front reports when it cannot give the client a
// daemon connection (TITAN_AGENT_ERROR contract, ADR-0009).
const (
	codeStateDir    = "E_STATE_DIR"    // the state dir cannot be created or is not private
	codeLock        = "E_LOCK"         // the OS refuses the single-instance lock (e.g. NFS)
	codeDaemonStart = "E_DAEMON_START" // no daemon answered within spawnWait
	codeAuth        = "E_AUTH"         // the state file is unreadable or the token is refused
	// The running daemon is an older version that does not speak this
	// front's handshake: it has to be stopped (--stop) for this one to start.
	codeAgentOutdated = "E_AGENT_OUTDATED"

	// Windows: the SSH session's job kills its processes on close and does not
	// let the daemon leave it.
	codeJobNoBreakaway = "E_JOB_NO_BREAKAWAY"

	// Mouse pad (--input, ADR-0016).
	codeInputUnsupported = "E_INPUT_UNSUPPORTED" // no injector for this system yet
	codeNoDesktop        = "E_NO_DESKTOP"        // the desktop helper did not start: nobody signed in
	codeDesktopTask      = "E_DESKTOP_TASK"      // the helper's scheduled task cannot be created or run
)

// agentError is a failure tagged with its error-contract code. main prints it
// as a single stable line, `TITAN_AGENT_ERROR <code> <message>`, on stderr so
// the client can explain why level 3 is unavailable.
type agentError struct {
	Code string
	Err  error
}

func (e *agentError) Error() string { return e.Code + ": " + e.Err.Error() }
func (e *agentError) Unwrap() error { return e.Err }

// contractLine renders e as its one-line stderr form.
func (e *agentError) contractLine() string {
	return "TITAN_AGENT_ERROR " + e.contractReason()
}

// contractReason is `<code> <message>` on one line, whatever the cause: the
// tail of contractLine, and the reason of a BYE that refuses a session.
func (e *agentError) contractReason() string {
	return e.Code + " " + strings.Join(strings.Fields(e.Err.Error()), " ")
}

func withCode(code string, err error) error { return &agentError{Code: code, Err: err} }
