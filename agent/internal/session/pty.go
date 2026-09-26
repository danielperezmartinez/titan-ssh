package session

// Error-contract codes for PTY creation failures (see the agent's
// TITAN_AGENT_ERROR contract in ADR-0009).
const (
	CodePty      = "E_PTY"       // the PTY or its shell could not be started
	CodeNoConPty = "E_NO_CONPTY" // Windows older than 10 1809 / Server 2019
)

// PtyError is a PTY creation failure tagged with its error-contract code, so
// the daemon can tell the client why no session could be opened.
type PtyError struct {
	Code string
	Err  error
}

func (e *PtyError) Error() string { return e.Code + ": " + e.Err.Error() }
func (e *PtyError) Unwrap() error { return e.Err }

func ptyError(code string, err error) error { return &PtyError{Code: code, Err: err} }
