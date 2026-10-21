//go:build unix

package x11

import (
	"bytes"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
)

// FindSession looks for the user's graphical session in the environment of
// their processes (/proc/<pid>/environ, readable for one's own processes):
// an SSH session has no DISPLAY of its own. See chooseSession.
func FindSession() (Session, error) {
	envs := scanProc("/proc", os.Getuid())
	return chooseSession(envs, os.Getenv("DISPLAY"), os.Getenv("HOME"))
}

// scanProc reads the environment of every process of uid under root.
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
		var e procEnv
		for _, kv := range bytes.Split(data, []byte{0}) {
			k, v, ok := strings.Cut(string(kv), "=")
			if !ok {
				continue
			}
			switch k {
			case "DISPLAY":
				e.display = v
			case "XAUTHORITY":
				e.xauthority = v
			case "WAYLAND_DISPLAY":
				e.wayland = v
			case "XDG_SESSION_TYPE":
				e.sessionType = v
			}
		}
		if e.display != "" || e.wayland != "" || e.sessionType != "" {
			out = append(out, e)
		}
	}
	return out
}
