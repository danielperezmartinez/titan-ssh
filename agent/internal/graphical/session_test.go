package graphical

import (
	"errors"
	"strings"
	"testing"
)

func logindFile(lines ...string) []byte { return []byte(strings.Join(lines, "\n") + "\n") }

func TestParseLogind(t *testing.T) {
	l := parseLogind("3", logindFile("# comment", "UID=1000", "USER=u", "ACTIVE=1", "STATE=active",
		"REMOTE=0", "TYPE=wayland", "CLASS=user", "SEAT=seat0", "DESKTOP=KDE", "LEADER=804"))
	want := logindSession{id: "3", uid: 1000, active: true, state: "active", typ: "wayland", class: "user", seat: "seat0", desktop: "KDE"}
	if l != want {
		t.Errorf("parsed %+v, want %+v", l, want)
	}
}

func TestParseEnviron(t *testing.T) {
	e, ok := parseEnviron([]byte("HOME=/h\x00XDG_SESSION_ID=1\x00WAYLAND_DISPLAY=wayland-1\x00DISPLAY=:0\x00" +
		"XDG_CURRENT_DESKTOP=KDE\x00XDG_RUNTIME_DIR=/run/user/7\x00DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/7/bus\x00"))
	want := procEnv{sessionID: "1", wayland: "wayland-1", display: ":0", desktop: "KDE", runtimeDir: "/run/user/7", dbus: "unix:path=/run/user/7/bus"}
	if !ok || e != want {
		t.Errorf("parsed %+v (%v), want %+v", e, ok, want)
	}
	if _, ok := parseEnviron([]byte("HOME=/h\x00XDG_SESSION_ID=4\x00")); ok {
		t.Error("an environment without a display counted")
	}
}

// kde is a KDE Plasma Wayland desktop on seat0, as logind and /proc show it.
func kde() env {
	return env{
		uid: 1000, home: "/home/u", haveLogind: true, runtimeBase: "/run/user",
		logind: []logindSession{
			{id: "1", uid: 1000, typ: "wayland", class: "user", seat: "seat0", state: "active", active: true, desktop: "KDE"},
			{id: "2", uid: 1000, typ: "unspecified", class: "manager", state: "active", active: true},
			{id: "67", uid: 1000, typ: "tty", class: "user", state: "active", active: true, remote: true},
		},
		procs: []procEnv{
			{sessionID: "1", wayland: "wayland-1", display: ":0", sessionType: "wayland", desktop: "KDE",
				runtimeDir: "/run/user/1000", dbus: "unix:path=/run/user/1000/bus"},
			{display: "localhost:10.0"}, // SSH X11 forwarding: not the desktop
		},
	}
}

func TestChooseWaylandFromLogind(t *testing.T) {
	s, err := choose(kde())
	want := Session{Kind: Wayland, ID: "1", Desktop: "KDE", Display: ":0", Xauthority: "/home/u/.Xauthority",
		WaylandDisplay: "wayland-1", RuntimeDir: "/run/user/1000", DBusAddress: "unix:path=/run/user/1000/bus"}
	if err != nil || s != want {
		t.Fatalf("session = %+v, %v; want %+v", s, err, want)
	}
	if s.Family() != FamilyKDE || s.Describe() != "KDE (Wayland)" {
		t.Errorf("family %v, describe %q", s.Family(), s.Describe())
	}
}

func TestChooseDefaultsWithoutProcesses(t *testing.T) {
	e := kde()
	e.procs = nil
	e.logind[0].desktop = "sway"
	s, err := choose(e)
	if err != nil || s.WaylandDisplay != "wayland-0" || s.RuntimeDir != "/run/user/1000" ||
		s.DBusAddress != "unix:path=/run/user/1000/bus" || s.Family() != FamilyWlroots {
		t.Errorf("session = %+v, %v", s, err)
	}
}

func TestChoosePrefersLocalActiveOverRemote(t *testing.T) {
	e := env{uid: 1000, haveLogind: true, logind: []logindSession{
		{id: "9", uid: 1000, typ: "x11", class: "user", state: "active", active: true, remote: true, display: ":10"},
		{id: "4", uid: 1000, typ: "x11", class: "user", seat: "seat0", state: "active", active: true, display: ":0", desktop: "GNOME"},
	}}
	s, err := choose(e)
	if err != nil || s.ID != "4" || s.Display != ":0" || s.Kind != X11 || s.Remote || s.Family() != FamilyGNOME {
		t.Errorf("session = %+v, %v", s, err)
	}
	// Alone, the xrdp session is used.
	e.logind = e.logind[:1]
	s, err = choose(e)
	if err != nil || s.ID != "9" || s.Display != ":10" || !s.Remote {
		t.Errorf("remote session = %+v, %v", s, err)
	}
}

func TestChooseInactiveSession(t *testing.T) {
	e := env{uid: 1000, haveLogind: true, logind: []logindSession{
		{id: "4", uid: 1000, typ: "wayland", class: "user", seat: "seat0", state: "online", desktop: "GNOME"},
		{id: "5", uid: 1001, typ: "wayland", class: "user", seat: "seat0", state: "active", active: true},
	}}
	_, err := choose(e)
	if !errors.Is(err, ErrInactive) || !strings.Contains(err.Error(), "another user has the desktop") {
		t.Errorf("err = %v, want ErrInactive for another user", err)
	}
}

func TestChooseLoginScreenAndOtherUser(t *testing.T) {
	greeter := env{uid: 1000, haveLogind: true, logind: []logindSession{
		{id: "c1", uid: 964, typ: "wayland", class: "greeter", seat: "seat0", state: "active", active: true},
	}}
	if _, err := choose(greeter); !errors.Is(err, ErrNoSession) || !strings.Contains(err.Error(), "login screen") {
		t.Errorf("greeter: %v", err)
	}
	other := env{uid: 1000, haveLogind: true, logind: []logindSession{
		{id: "5", uid: 1001, typ: "x11", class: "user", seat: "seat0", state: "active", active: true},
	}}
	if _, err := choose(other); !errors.Is(err, ErrInactive) {
		t.Errorf("other user: %v", err)
	}
	if _, err := choose(env{uid: 1000, haveLogind: true}); err != ErrNoSession {
		t.Errorf("nothing: %v", err)
	}
}

func TestChooseFallsBackToProcesses(t *testing.T) {
	// A server started by hand (Xvfb in a container) has no logind session.
	e := env{uid: 1000, home: "/home/u", haveLogind: true, procs: []procEnv{
		{display: ":1", xauthority: "/x/auth1"},
		{display: ":0"},
		{display: ":1"},
		{display: "localhost:10.0"},
	}}
	s, err := choose(e)
	if err != nil || s.Kind != X11 || s.Display != ":1" || s.Xauthority != "/x/auth1" {
		t.Errorf("session = %+v, %v", s, err)
	}
	s, err = choose(env{home: "/home/u", procs: []procEnv{{display: ":0"}}})
	if err != nil || s.Xauthority != "/home/u/.Xauthority" {
		t.Errorf("default Xauthority: %+v, %v", s, err)
	}
	s, _ = choose(env{procs: []procEnv{{display: ":0", wayland: "wayland-0", desktop: "GNOME"}}})
	if s.Kind != Wayland || s.Desktop != "GNOME" {
		t.Errorf("a Wayland session with XWayland was seen as %+v", s)
	}
	s, _ = choose(env{procs: []procEnv{{wayland: "wayland-1", desktop: "Hyprland"}}})
	if s.Kind != Wayland || s.WaylandDisplay != "wayland-1" || s.Family() != FamilyWlroots {
		t.Errorf("Wayland without XWayland: %+v", s)
	}
	if _, err := choose(env{ownDisplay: "localhost:10.0"}); err != ErrNoSession {
		t.Errorf("no session: %v", err)
	}
	s, err = choose(env{ownDisplay: ":2"})
	if err != nil || s.Display != ":2" {
		t.Errorf("own display: %+v, %v", s, err)
	}
}

func TestFamily(t *testing.T) {
	for desktop, want := range map[string]Family{
		"KDE": FamilyKDE, "ubuntu:GNOME": FamilyGNOME, "GNOME-Classic:GNOME": FamilyGNOME,
		"sway": FamilyWlroots, "Hyprland": FamilyWlroots, "XFCE": FamilyOther, "": FamilyOther,
	} {
		if got := (Session{Desktop: desktop}).Family(); got != want {
			t.Errorf("%q: family %v, want %v", desktop, got, want)
		}
	}
}
