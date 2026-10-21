package x11

import (
	"errors"
	"path/filepath"
	"sort"
	"strconv"
)

// Session is the user's graphical session as found from the processes they
// run: an SSH session has no DISPLAY of its own.
type Session struct {
	Display    Display
	Xauthority string
	// Wayland is set when the session is Wayland: its DISPLAY, if any, is
	// XWayland, which shows only X clients and cannot drive the desktop.
	Wayland bool
}

// ErrNoSession reports that the user has no graphical session running.
var ErrNoSession = errors.New("no graphical session of this user was found: sign in to the desktop first")

// procEnv is one process's environment, the variables that matter here.
type procEnv struct {
	display, xauthority, wayland, sessionType string
}

// chooseSession picks the session from the processes' environments: the
// display most of them use wins; its XAUTHORITY comes from the same processes,
// or ~/.Xauthority. Without any, it falls back to ownDisplay if it is local.
func chooseSession(envs []procEnv, ownDisplay, home string) (Session, error) {
	counts := map[string]int{}
	wayland := false
	for _, e := range envs {
		if e.wayland != "" || e.sessionType == "wayland" {
			wayland = true
		}
		if d, err := ParseDisplay(e.display); err == nil {
			counts[d.Number]++
		}
	}
	if len(counts) == 0 {
		if wayland {
			return Session{Wayland: true}, nil
		}
		if d, err := ParseDisplay(ownDisplay); err == nil {
			return Session{Display: d, Xauthority: defaultXauthority(home)}, nil
		}
		return Session{}, ErrNoSession
	}
	numbers := make([]string, 0, len(counts))
	for n := range counts {
		numbers = append(numbers, n)
	}
	sort.Slice(numbers, func(i, j int) bool {
		if counts[numbers[i]] != counts[numbers[j]] {
			return counts[numbers[i]] > counts[numbers[j]]
		}
		a, _ := strconv.Atoi(numbers[i])
		b, _ := strconv.Atoi(numbers[j])
		return a < b
	})
	s := Session{Display: Display{Number: numbers[0]}, Wayland: wayland}
	for _, e := range envs {
		if d, err := ParseDisplay(e.display); err == nil && d.Number == s.Display.Number && e.xauthority != "" {
			s.Xauthority = e.xauthority
			break
		}
	}
	if s.Xauthority == "" {
		s.Xauthority = defaultXauthority(home)
	}
	return s, nil
}

func defaultXauthority(home string) string {
	if home == "" {
		return ""
	}
	return filepath.Join(home, ".Xauthority")
}
