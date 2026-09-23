// titan-agent — resilience level-3 helper (ADR-0008).
//
// A small static binary the titan-ssh client `exec`s over an already
// authenticated SSH channel (option B, no SSH server of its own). It keeps a
// PTY alive per session, survives client disconnects, buffers output and
// replays from an offset on reconnect. Build multi-arch with:
//
//	GOOS=linux  GOARCH=amd64 go build -o dist/titan-agent-linux-amd64 ./cmd/titan-agent
//	GOOS=linux  GOARCH=arm64 go build -o dist/titan-agent-linux-arm64 ./cmd/titan-agent
//
// The PTY (internal/session/pty_unix.go) uses github.com/creack/pty; that file
// only builds on unix, so the module still builds/tests on non-unix hosts.
module github.com/danigar/titan-ssh/agent

go 1.22

require github.com/creack/pty v1.1.24
