package main

import "strings"

// Error-contract codes the front reports when it cannot give the client a
// daemon connection (TITAN_AGENT_ERROR contract, ADR-0009).
const (
	codeStateDir    = "E_STATE_DIR"    // the state dir cannot be created or is not private
	codeLock        = "E_LOCK"         // the OS refuses the single-instance lock (e.g. NFS)
	codeDaemonStart = "E_DAEMON_START" // no daemon answered within spawnWait
	codeAuth        = "E_AUTH"         // the state file is unreadable or the token is refused

	// Windows: the SSH session's job kills its processes on close and does not
	// let the daemon leave it.
	codeJobNoBreakaway = "E_JOB_NO_BREAKAWAY"
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
	msg := strings.Join(strings.Fields(e.Err.Error()), " ") // one line, whatever the cause
	return "TITAN_AGENT_ERROR " + e.Code + " " + msg
}

func withCode(code string, err error) error { return &agentError{Code: code, Err: err} }
