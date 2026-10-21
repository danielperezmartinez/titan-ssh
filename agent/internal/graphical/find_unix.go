//go:build unix

package graphical

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
)

// logindDir is where systemd-logind keeps one file per session.
const logindDir = "/run/systemd/sessions"

// Find returns the user's graphical session (see choose). Errors wrap
// ErrNoSession or ErrInactive, with the reason.
func Find() (Session, error) {
	uid := os.Getuid()
	logind, have := readLogind(logindDir)
	return choose(env{
		uid:         uid,
		home:        os.Getenv("HOME"),
		ownDisplay:  os.Getenv("DISPLAY"),
		haveLogind:  have,
		logind:      logind,
		procs:       scanProc("/proc", uid),
		runtimeBase: "/run/user",
	})
}

// readLogind reads every session file in dir; have is false without logind.
func readLogind(dir string) (sessions []logindSession, have bool) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, false
	}
	for _, ent := range entries {
		name := ent.Name()
		if ent.IsDir() || strings.Contains(name, ".") {
			continue // *.ref and the like
		}
		data, err := os.ReadFile(filepath.Join(dir, name))
		if err != nil {
			continue
		}
		sessions = append(sessions, parseLogind(name, data))
	}
	return sessions, true
}

// scanProc reads the environment of every process of uid under root
// (/proc/<pid>/environ, readable for one's own processes).
func scanProc(root string, uid int) []procEnv {
	entries, err := os.ReadDir(root)
	if err != nil {
		return nil
	}
	self := strconv.Itoa(os.Getpid())
	var out []procEnv
	for _, ent := range entries {
		name := ent.Name()
		if name == self || name[0] < '0' || name[0] > '9' {
			continue
		}
		dir := filepath.Join(root, name)
		fi, err := os.Stat(dir)
		if err != nil {
			continue
		}
		if st, ok := fi.Sys().(*syscall.Stat_t); !ok || int(st.Uid) != uid {
			continue
		}
		data, err := os.ReadFile(filepath.Join(dir, "environ"))
		if err != nil || len(data) == 0 {
			continue
		}
		if e, ok := parseEnviron(data); ok {
			out = append(out, e)
		}
	}
	return out
}
