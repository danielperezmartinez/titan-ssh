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
// The PTY (internal/session/pty_*.go) is the agent's own thin wrapper over the
// OS: /dev/ptmx or posix_openpt on Linux, macOS and FreeBSD, ConPTY on Windows.
// Its only dependency is golang.org/x/sys, maintained by the Go project
// (ADR-0009 §4); any other dependency needs an ADR.
module github.com/danielperezmartinez/titan-ssh/agent

go 1.26.0

require golang.org/x/sys v0.48.0
