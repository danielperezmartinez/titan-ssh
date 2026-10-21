package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/screen"
)

// A remote desktop session (ADR-0029) is `titan-agent --screen` on the SSH
// exec channel: the screen goes out as tiles, the client's input comes in on
// the same channel. Each one holds a lock file, screen-<pid>.lock, for as
// long as it runs, so --status can tell how many are watching the screen.
const screenLockPrefix = "screen-"

// screenViewer is one running --screen, as --status reports it.
type screenViewer struct {
	PID       int   `json:"pid"`
	StartedMs int64 `json:"startedMs"`
}

// screenStateDir is the state dir of the running --screen, for the ways that
// keep something there (the portal's restore token).
var screenStateDir string

// runScreen is the --screen mode.
func runScreen(stateDir string) error {
	if !screenServed {
		return withCode(codeScreenUnsupported, errors.New("this agent does not serve the remote desktop: it is only in titan-ssh canary"))
	}
	if err := ensureStateDir(stateDir); err != nil {
		return withCode(codeStateDir, err)
	}
	screenStateDir = stateDir
	release := registerViewer(stateDir)
	defer release()
	if relayed, err := relayScreen(stateDir); relayed {
		return err
	}
	src, err := openScreen()
	if err != nil {
		return err
	}
	defer src.close()
	return screen.Serve(stdio(), src.capturer, src.input, screen.Options{})
}

// stdio is this process's stdin and stdout, the SSH exec channel.
func stdio() io.ReadWriter {
	return struct {
		io.Reader
		io.Writer
	}{os.Stdin, os.Stdout}
}

// screenSource is what openScreen gives a session: the capturer, the
// injector for the input and what to close at the end.
type screenSource struct {
	capturer screen.Capturer
	input    screen.InputHandler
	close    func()
}

// registerViewer takes this process's viewer lock and returns its release.
// A failure only hides the session from --status.
func registerViewer(stateDir string) func() {
	name := screenLockPrefix + strconv.Itoa(os.Getpid()) + ".lock"
	f, err := lockNamed(stateDir, name)
	if err != nil {
		return func() {}
	}
	data, _ := json.Marshal(screenViewer{PID: os.Getpid(), StartedMs: time.Now().UnixMilli()})
	_ = f.Truncate(0)
	_, _ = f.WriteAt(data, 0)
	return func() {
		_ = os.Remove(filepath.Join(stateDir, name))
		f.Close()
	}
}

// screenViewers lists the --screen sessions running now, oldest first, and
// deletes the lock files of those that ended without cleaning up.
func screenViewers(stateDir string) []screenViewer {
	matches, _ := filepath.Glob(filepath.Join(stateDir, screenLockPrefix+"*.lock"))
	var out []screenViewer
	for _, m := range matches {
		name := filepath.Base(m)
		if _, err := strconv.Atoi(strings.TrimSuffix(strings.TrimPrefix(name, screenLockPrefix), ".lock")); err != nil {
			continue
		}
		held, err := lockNamedHeld(stateDir, name)
		if err != nil {
			continue
		}
		if !held {
			_ = os.Remove(m)
			continue
		}
		var v screenViewer
		if data, err := os.ReadFile(m); err == nil && json.Unmarshal(data, &v) == nil {
			out = append(out, v)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].StartedMs < out[j].StartedMs })
	return out
}

// describeViewers is the line --status prints about them.
func describeViewers(v []screenViewer) string {
	if len(v) == 0 {
		return ""
	}
	return fmt.Sprintf("screen: %d remote desktop session(s) watching", len(v))
}
