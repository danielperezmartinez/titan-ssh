// Command titan-agent is the titan-ssh resilience level-3 helper (ADR-0008).
//
// Two modes of the same binary:
//
//   - front (default): the client `exec`s this over the SSH channel. It dials
//     the per-user daemon over a Unix socket (starting it, detached, on first
//     use) and splices the exec channel's stdio to that socket. The framed
//     protocol (package protocol) flows through untouched; the front is a dumb
//     pipe, so it can come and go with each (re)connection.
//   - daemon (--daemon): the persistent process that holds every session's PTY
//     and output ring buffer and speaks the framed protocol per connection. It
//     survives client disconnects, so reconnecting re-attaches to a live PTY and
//     replays from the client's last offset.
package main

import (
	"flag"
	"fmt"
	"os"
	"path/filepath"
)

// version is stamped into the install path so versions can coexist and the
// client can verify the expected binary by checksum (ADR-0008 §5). It is the
// single app version, set at build time with -ldflags "-X main.version=..." by
// the Gradle build; a plain `go build` reports the development value.
var version = "0.0.0-dev"

func main() {
	daemon := flag.Bool("daemon", false, "run the persistent session daemon")
	// Carried in the HELLO frame now; accepted for the documented exec command
	// but not required by the front (the daemon reads the id from the protocol).
	_ = flag.String("session", "", "stable session id (informational; id travels in HELLO)")
	bufCap := flag.Int("buffer-bytes", 4*1024*1024, "per-session output ring buffer size")
	sock := flag.String("socket", defaultSocketPath(), "daemon control socket path")
	showVer := flag.Bool("version", false, "print version and exit")
	flag.Parse()

	if *showVer {
		fmt.Println("titan-agent", version)
		return
	}

	var err error
	if *daemon {
		err = runDaemon(*sock, *bufCap)
	} else {
		err = runFront(*sock)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "titan-agent:", err)
		os.Exit(1)
	}
}

// defaultSocketPath is the per-user daemon socket, under $XDG_RUNTIME_DIR when
// set (tmpfs, 0700) or a uid-scoped temp dir otherwise.
func defaultSocketPath() string {
	dir := os.Getenv("XDG_RUNTIME_DIR")
	if dir == "" {
		dir = filepath.Join(os.TempDir(), fmt.Sprintf("titan-ssh-%d", os.Getuid()))
	}
	return filepath.Join(dir, "titan-agent.sock")
}

