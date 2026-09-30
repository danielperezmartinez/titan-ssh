// Command titan-agent is the titan-ssh resilience level-3 helper (ADR-0008,
// ADR-0009).
//
// Two modes of the same binary:
//
//   - front (default): the client `exec`s this over the SSH channel. It reads
//     the per-user daemon's state file, connects to it over TCP on 127.0.0.1
//     with the token from that file (starting the daemon, detached, on first
//     use) and splices the exec channel's stdio to that connection. The framed
//     protocol (package protocol) flows through untouched; the front is a dumb
//     pipe, so it can come and go with each (re)connection.
//   - daemon (--daemon): the persistent process that holds every session's PTY
//     and output ring buffer and speaks the framed protocol per connection. It
//     survives client disconnects, so reconnecting re-attaches to a live PTY and
//     replays from the client's last offset. A lock in the state dir keeps it to
//     one per user.
//
// --status reports the daemon and its sessions (--json for the client),
// --preview prints the end of one session's output (for the client's preview),
// --close-session ends one session and --stop ends the daemon in order,
// closing every session first. Nothing else ever ends them: sessions do not
// expire (ADR-0014).
//
// When the front cannot give the client a daemon connection it exits non-zero
// with one stderr line, `TITAN_AGENT_ERROR <code> <message>` (see errors.go).
package main

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
)

// version is stamped into the install path so versions can coexist and the
// client can verify the expected binary by checksum (ADR-0008 §5). It is the
// single app version, set at build time with -ldflags "-X main.version=..." by
// the Gradle build; a plain `go build` reports the development value.
var version = "0.0.0-dev"

func main() {
	daemon := flag.Bool("daemon", false, "run the persistent session daemon")
	stop := flag.Bool("stop", false, "stop the user's daemon, closing every session, and exit")
	status := flag.Bool("status", false, "print the user's daemon and its sessions, and exit")
	asJSON := flag.Bool("json", false, "with --status or --preview: print the report as JSON")
	closeID := flag.String("close-session", "", "close the session with this id, and exit")
	previewID := flag.String("preview", "", "print the end of this session's output, and exit")
	// Carried in the HELLO frame now; accepted for the documented exec command
	// but not required by the front (the daemon reads the id from the protocol).
	_ = flag.String("session", "", "stable session id (informational; id travels in HELLO)")
	bufCap := flag.Int("buffer-bytes", 4*1024*1024, "per-session output ring buffer size")
	stateDir := flag.String("state-dir", "", "per-user state directory (default: the platform's state dir)")
	showVer := flag.Bool("version", false, "print version and exit")
	flag.Parse()

	if *showVer {
		fmt.Println("titan-agent", version)
		return
	}

	err := run(*stateDir, mode{
		daemon: *daemon, stop: *stop, status: *status, json: *asJSON,
		closeID: *closeID, previewID: *previewID,
	}, *bufCap)
	if err != nil {
		var ae *agentError
		if errors.As(err, &ae) {
			fmt.Fprintln(os.Stderr, ae.contractLine())
		} else {
			fmt.Fprintln(os.Stderr, "titan-agent:", err)
		}
		os.Exit(1)
	}
}

// mode is what the command line asks for; with none of them set, the binary
// runs as the front.
type mode struct {
	daemon, stop, status, json bool
	closeID, previewID         string
}

func run(stateDir string, m mode, bufCap int) error {
	if stateDir == "" {
		dir, err := defaultStateDir()
		if err != nil {
			return withCode(codeStateDir, err)
		}
		stateDir = dir
	}
	switch {
	case m.stop:
		return stopDaemon(stateDir)
	case m.status:
		st, err := queryStatus(stateDir)
		if err != nil {
			return err
		}
		if m.json {
			return json.NewEncoder(os.Stdout).Encode(st)
		}
		printStatus(os.Stdout, st)
		return nil
	case m.closeID != "":
		closed, err := closeSession(stateDir, m.closeID)
		if err != nil {
			return err
		}
		if !closed {
			fmt.Println("titan-agent: no such session; nothing to close")
		}
		return nil
	case m.previewID != "":
		p, err := queryPreview(stateDir, m.previewID)
		if err != nil {
			return err
		}
		if m.json {
			return json.NewEncoder(os.Stdout).Encode(p)
		}
		// The raw bytes: a terminal draws them as the session looks.
		_, err = os.Stdout.Write(p.Data)
		return err
	case m.daemon:
		return runDaemon(stateDir, bufCap)
	default:
		return runFront(stateDir)
	}
}
