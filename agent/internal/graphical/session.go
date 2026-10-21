// Package graphical finds the user's graphical session: the desktop the
// remote desktop and the mouse pad act on (ADR-0031 §1). An SSH session has
// no DISPLAY or WAYLAND_DISPLAY of its own, so it is looked up in logind and,
// without it, in the environment of the user's processes.
package graphical

import (
	"bytes"
	"errors"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

// Kind is the display protocol of a session.
type Kind int

const (
	X11 Kind = iota + 1
	Wayland
)

func (k Kind) String() string {
	switch k {
	case X11:
		return "X11"
	case Wayland:
		return "Wayland"
	}
	return "unknown"
}

// Family groups the desktops that share a way to reach their screen.
type Family int

const (
	FamilyOther Family = iota
	FamilyKDE
	FamilyGNOME
	// FamilyWlroots is sway, Hyprland and the other compositors that speak the
	// wlroots protocols.
	FamilyWlroots
)

// Session is the user's graphical session.
type Session struct {
	Kind Kind
	// ID is the logind session id; "" when found without logind.
	ID string
	// Desktop is what the session calls its desktop (XDG_CURRENT_DESKTOP or
	// logind's DESKTOP), e.g. "KDE", "GNOME", "sway"; "" when unknown.
	Desktop string
	// Display is the X11 display (":0"): the server itself on X11, XWayland
	// on Wayland, where it shows only X clients.
	Display    string
	Xauthority string
	// WaylandDisplay is the socket name under RuntimeDir ("wayland-0").
	WaylandDisplay string
	RuntimeDir     string
	// DBusAddress is the session bus (DBUS_SESSION_BUS_ADDRESS).
	DBusAddress string
	// Remote is set for a session without a local seat (xrdp and the like).
	Remote bool
}

// Family tells which desktop family the session runs.
func (s Session) Family() Family {
	for _, part := range strings.Split(strings.ToLower(s.Desktop), ":") {
		switch part {
		case "kde":
			return FamilyKDE
		case "gnome", "gnome-classic", "gnome-flashback", "ubuntu", "pop", "unity":
			return FamilyGNOME
		case "sway", "hyprland", "river", "labwc", "wayfire", "dwl", "qtile", "cage", "wlroots":
			return FamilyWlroots
		}
	}
	return FamilyOther
}

// Describe names the session for messages: "KDE (Wayland)".
func (s Session) Describe() string {
	name := s.Desktop
	if name == "" {
		name = "the desktop"
	}
	return name + " (" + s.Kind.String() + ")"
}

// ErrNoSession reports that the user has no graphical session running.
var ErrNoSession = errors.New("no graphical session of this user was found: sign in to the desktop first")

// ErrInactive reports a session that exists but is not on screen.
var ErrInactive = errors.New("the user's graphical session is not on screen")

// reasonError adds why to one of the errors above.
type reasonError struct {
	base error
	why  string
}

func (e *reasonError) Error() string { return e.base.Error() + ": " + e.why }
func (e *reasonError) Unwrap() error { return e.base }

// logindSession is one /run/systemd/sessions/<id> file, the fields used here.
type logindSession struct {
	id, typ, class, seat, state, desktop, display string
	uid                                           int
	remote, active                                bool
}

// graphical tells whether the session runs a display server.
func (l logindSession) graphical() bool { return l.typ == "x11" || l.typ == "wayland" }

// parseLogind reads the KEY=value lines of a logind session file.
func parseLogind(id string, data []byte) logindSession {
	l := logindSession{id: id, uid: -1}
	for _, line := range strings.Split(string(data), "\n") {
		k, v, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		switch k {
		case "UID":
			if n, err := strconv.Atoi(v); err == nil {
				l.uid = n
			}
		case "TYPE":
			l.typ = v
		case "CLASS":
			l.class = v
		case "SEAT":
			l.seat = v
		case "STATE":
			l.state = v
		case "DESKTOP":
			l.desktop = v
		case "DISPLAY":
			l.display = v
		case "REMOTE":
			l.remote = v == "1"
		case "ACTIVE":
			l.active = v == "1"
		}
	}
	return l
}

// procEnv is one process's environment, the variables that matter here.
type procEnv struct {
	sessionID, display, xauthority, wayland, sessionType string
	desktop, runtimeDir, dbus                            string
}

// localDisplay tells whether d is a display of a local X server (":0"), not
// SSH X11 forwarding ("localhost:10.0").
func localDisplay(d string) bool {
	host, rest, ok := strings.Cut(d, ":")
	if !ok || (host != "" && host != "unix") {
		return false
	}
	num, _, _ := strings.Cut(rest, ".")
	_, err := strconv.Atoi(num)
	return num != "" && err == nil
}

// displayNumber is ":1.0" → "1", to compare displays.
func displayNumber(d string) string {
	_, rest, _ := strings.Cut(d, ":")
	num, _, _ := strings.Cut(rest, ".")
	return num
}

// env is what the lookup reads from the system.
type env struct {
	uid         int
	home        string
	ownDisplay  string // this process's DISPLAY
	haveLogind  bool
	logind      []logindSession
	procs       []procEnv
	runtimeBase string // "/run/user"
}

// choose picks the session: from logind when it knows one of this user's
// graphical sessions, from the processes' environments otherwise.
func choose(e env) (Session, error) {
	logindErr := ErrNoSession
	if e.haveLogind {
		s, err, found := chooseLogind(e)
		if found {
			return s, err
		}
		logindErr = err
	}
	s, err := chooseProcs(e)
	if errors.Is(err, ErrNoSession) {
		return Session{}, logindErr
	}
	return s, err
}

// chooseLogind ranks this user's graphical sessions: active on a local seat,
// then remote, then local but switched away. found is false when the user has
// none; err then says what logind shows instead.
func chooseLogind(e env) (Session, error, bool) {
	var mine []logindSession
	for _, l := range e.logind {
		if l.uid == e.uid && l.graphical() && l.class == "user" && l.state != "closing" {
			mine = append(mine, l)
		}
	}
	if len(mine) == 0 {
		return Session{}, othersOnSeat(e), false
	}
	rank := func(l logindSession) int {
		switch {
		case l.seat != "" && !l.remote && l.active:
			return 0
		case l.seat == "" || l.remote:
			return 1
		}
		return 2
	}
	sort.SliceStable(mine, func(i, j int) bool {
		if rank(mine[i]) != rank(mine[j]) {
			return rank(mine[i]) < rank(mine[j])
		}
		a, _ := strconv.Atoi(mine[i].id)
		b, _ := strconv.Atoi(mine[j].id)
		return a < b
	})
	l := mine[0]
	s := fromLogind(e, l)
	if rank(l) == 2 {
		why := "it is switched away from the screen"
		if other := othersOnSeat(e); errors.Is(other, ErrInactive) {
			why = "another user has the desktop on " + l.seat
		}
		return s, &reasonError{ErrInactive, why}, true
	}
	return s, nil, true
}

// othersOnSeat explains a seat without this user's session: a login screen,
// or another user's desktop.
func othersOnSeat(e env) error {
	for _, l := range e.logind {
		if l.seat == "" || !l.active || l.state == "closing" {
			continue
		}
		if l.class == "greeter" {
			return &reasonError{ErrNoSession, "the screen shows the login screen"}
		}
		if l.uid != e.uid && l.graphical() && l.class == "user" {
			return &reasonError{ErrInactive, "another user is signed in on the desktop"}
		}
	}
	return ErrNoSession
}

// fromLogind fills the session from the environment of its processes (those
// with its XDG_SESSION_ID), and the defaults where they say nothing.
func fromLogind(e env, l logindSession) Session {
	s := Session{ID: l.id, Desktop: l.desktop, Remote: l.remote || l.seat == ""}
	if l.typ == "wayland" {
		s.Kind = Wayland
	} else {
		s.Kind = X11
	}
	var own []procEnv
	for _, p := range e.procs {
		if p.sessionID == l.id {
			own = append(own, p)
		}
	}
	fill(&s, own)
	if s.Display == "" && localDisplay(l.display) {
		s.Display = l.display
	}
	defaults(&s, e)
	return s
}

// chooseProcs is the lookup without logind: the display most processes use
// wins, as before ADR-0031.
func chooseProcs(e env) (Session, error) {
	counts := map[string]int{}
	wayland := false
	var waylandEnvs []procEnv
	for _, p := range e.procs {
		if p.wayland != "" || p.sessionType == "wayland" {
			wayland = true
			waylandEnvs = append(waylandEnvs, p)
		}
		if localDisplay(p.display) {
			counts[displayNumber(p.display)]++
		}
	}
	if len(counts) == 0 {
		if wayland {
			s := Session{Kind: Wayland}
			fill(&s, waylandEnvs)
			defaults(&s, e)
			return s, nil
		}
		if localDisplay(e.ownDisplay) {
			s := Session{Kind: X11, Display: e.ownDisplay}
			defaults(&s, e)
			return s, nil
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
	var own []procEnv
	for _, p := range e.procs {
		if localDisplay(p.display) && displayNumber(p.display) == numbers[0] {
			own = append(own, p)
		}
	}
	s := Session{Kind: X11}
	if wayland {
		// A DISPLAY next to a Wayland session is XWayland.
		s.Kind = Wayland
	}
	fill(&s, own)
	s.Display = ":" + numbers[0]
	defaults(&s, e)
	return s, nil
}

// fill takes each variable from the first process that has it.
func fill(s *Session, envs []procEnv) {
	for _, p := range envs {
		if s.Display == "" && localDisplay(p.display) {
			s.Display = ":" + displayNumber(p.display)
		}
		set := func(dst *string, v string) {
			if *dst == "" {
				*dst = v
			}
		}
		set(&s.Xauthority, p.xauthority)
		set(&s.WaylandDisplay, p.wayland)
		set(&s.Desktop, p.desktop)
		set(&s.RuntimeDir, p.runtimeDir)
		set(&s.DBusAddress, p.dbus)
		if s.Kind == 0 && p.sessionType == "wayland" {
			s.Kind = Wayland
		}
	}
}

// defaults fills what the processes did not say with the usual places.
func defaults(s *Session, e env) {
	if s.RuntimeDir == "" && e.runtimeBase != "" {
		s.RuntimeDir = filepath.Join(e.runtimeBase, strconv.Itoa(e.uid))
	}
	if s.DBusAddress == "" && s.RuntimeDir != "" {
		s.DBusAddress = "unix:path=" + filepath.Join(s.RuntimeDir, "bus")
	}
	if s.Kind == Wayland && s.WaylandDisplay == "" {
		s.WaylandDisplay = "wayland-0"
	}
	if s.Xauthority == "" && e.home != "" {
		s.Xauthority = filepath.Join(e.home, ".Xauthority")
	}
}

// parseEnviron picks the variables that matter from a NUL-separated
// environment; ok is false when it has none of a graphical session.
func parseEnviron(data []byte) (procEnv, bool) {
	var e procEnv
	for _, kv := range bytes.Split(data, []byte{0}) {
		k, v, ok := strings.Cut(string(kv), "=")
		if !ok {
			continue
		}
		switch k {
		case "XDG_SESSION_ID":
			e.sessionID = v
		case "DISPLAY":
			e.display = v
		case "XAUTHORITY":
			e.xauthority = v
		case "WAYLAND_DISPLAY":
			e.wayland = v
		case "XDG_SESSION_TYPE":
			e.sessionType = v
		case "XDG_CURRENT_DESKTOP":
			e.desktop = v
		case "XDG_RUNTIME_DIR":
			e.runtimeDir = v
		case "DBUS_SESSION_BUS_ADDRESS":
			e.dbus = v
		}
	}
	return e, e.display != "" || e.wayland != "" || e.sessionType == "x11" || e.sessionType == "wayland"
}
