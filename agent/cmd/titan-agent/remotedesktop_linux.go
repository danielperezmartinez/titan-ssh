//go:build linux

package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/dbus"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/graphical"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/portal"
	"golang.org/x/sys/unix"
)

// The remote desktop's consent on the destination (ADR-0031 §3), as the agent
// panel shows and changes it: --status reports it, --remote-desktop changes it
// and --screen --unattended turns the unattended access on before serving.

// Consent kinds of remoteDesktopReport.
const (
	consentNone   = "none"   // X11 and wlroots: no dialog
	consentDialog = "dialog" // the portal asks the desktop's user
)

// remoteDesktopReport is the remote desktop part of --status --json.
type remoteDesktopReport struct {
	// Desktop names the user's graphical session, "KDE (Wayland)".
	Desktop string `json:"desktop"`
	Consent string `json:"consent"`
	// Remembered: a restore token is saved, so the dialog will not show.
	Remembered bool `json:"remembered,omitempty"`
	// Entry: the agent's hidden desktop entry is installed.
	Entry bool `json:"entry,omitempty"`
	// Unattended: the agent is pre-authorized, the dialog never shows.
	Unattended bool `json:"unattended,omitempty"`
	// UnattendedAvailable: this desktop lets the agent be pre-authorized.
	UnattendedAvailable bool `json:"unattendedAvailable,omitempty"`
}

// Remote desktop actions of --remote-desktop.
const (
	rdUnattendedOn  = "unattended-on"
	rdUnattendedOff = "unattended-off"
	rdForget        = "forget"
)

// screenUnattended is --screen --unattended.
var screenUnattended bool

const (
	permStoreName  = "org.freedesktop.impl.portal.PermissionStore"
	permStorePath  = dbus.ObjectPath("/org/freedesktop/impl/portal/PermissionStore")
	permStoreIface = "org.freedesktop.impl.portal.PermissionStore"
	// KDE's portal skips the dialog for the app ids with "yes" here (as for
	// its own RDP server).
	kdeAuthTable = "kde-authorized"
	kdeAuthID    = "remote-desktop"
	// The portal's own table of remembered sessions, keyed by restore token.
	portalTable = "remote-desktop"
)

// queryRemoteDesktop reports the remote desktop's consent; nil when the
// user has no graphical session.
func queryRemoteDesktop(stateDir string) *remoteDesktopReport {
	s, err := graphical.Find()
	if err != nil {
		return nil
	}
	r := &remoteDesktopReport{Desktop: s.Describe(), Consent: consentNone}
	if s.Kind != graphical.Wayland || s.Family() == graphical.FamilyWlroots {
		return r
	}
	r.Consent = consentDialog
	_, r.Entry = ownDesktopEntry()
	r.Remembered = readTokenFile(stateDir, portalTokenFile) != "" || readTokenFile(stateDir, portalInputTokenFile) != ""
	if bus, err := dbus.Dial(sessionBus(s)); err == nil {
		defer bus.Close()
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		switch s.Family() {
		case graphical.FamilyKDE:
			r.UnattendedAvailable = true
			r.Unattended, _ = kdeAuthorized(ctx, bus)
		case graphical.FamilyGNOME:
			r.UnattendedAvailable = portal.MutterAvailable(ctx, bus)
			r.Unattended = unattendedMarked(stateDir)
		}
	}
	return r
}

// runRemoteDesktop is --remote-desktop <action>.
func runRemoteDesktop(stateDir, action string) error {
	if err := ensureStateDir(stateDir); err != nil {
		return withCode(codeStateDir, err)
	}
	s, err := findDesktop()
	if err != nil {
		return err
	}
	if s.Kind != graphical.Wayland || s.Family() == graphical.FamilyWlroots {
		if action == rdForget {
			return nil // nothing is kept for X11 or wlroots
		}
		return withCode(codeScreenUnsupported, fmt.Errorf("%s asks no consent: there is no unattended access to set", s.Describe()))
	}
	bus, err := dbus.Dial(sessionBus(s))
	if err != nil {
		return withCode(codeScreenCapture, fmt.Errorf("the session bus: %w", err))
	}
	defer bus.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	screenStateDir = stateDir
	switch action {
	case rdUnattendedOn:
		return setUnattended(ctx, bus, s)
	case rdUnattendedOff:
		return clearUnattended(ctx, bus, s)
	case rdForget:
		var errs []error
		errs = append(errs, clearUnattended(ctx, bus, s))
		for _, name := range []string{portalTokenFile, portalInputTokenFile} {
			if tok := readTokenFile(stateDir, name); tok != "" {
				errs = append(errs, forgetPortalSession(ctx, bus, tok))
				errs = append(errs, ignoreMissing(os.Remove(filepath.Join(stateDir, name))))
			}
		}
		errs = append(errs, ignoreMissing(removeDesktopEntry()))
		if err := errors.Join(errs...); err != nil {
			return withCode(codeScreenCapture, err)
		}
		return nil
	}
	return fmt.Errorf("unknown --remote-desktop action %q (want %s, %s or %s)", action, rdUnattendedOn, rdUnattendedOff, rdForget)
}

func ignoreMissing(err error) error {
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	return err
}

// unattendedMarkFile marks the unattended access on GNOME, where it is the
// agent's own choice to take Mutter's API instead of the portal.
const unattendedMarkFile = "unattended"

func unattendedMarked(stateDir string) bool {
	_, err := os.Stat(filepath.Join(stateDir, unattendedMarkFile))
	return err == nil
}

// gnomeUnattended tells whether the running --screen or --input should take
// Mutter's API.
func gnomeUnattended() bool { return screenStateDir != "" && unattendedMarked(screenStateDir) }

// setUnattended pre-authorizes the agent: on KDE, its app id in the
// permission store; on GNOME, the mark that makes it use Mutter's own API.
// Other desktops have no way.
func setUnattended(ctx context.Context, bus *dbus.Conn, s graphical.Session) error {
	if s.Family() == graphical.FamilyGNOME {
		if !portal.MutterAvailable(ctx, bus) {
			return withCode(codeScreenUnsupported, fmt.Errorf("%s does not offer Mutter's remote desktop API", s.Describe()))
		}
		if screenStateDir == "" {
			return errors.New("no state dir")
		}
		f, err := createPrivateTemp(screenStateDir, ".unattended-*")
		if err != nil {
			return withCode(codeStateDir, err)
		}
		f.Close()
		return renameRetrying(f.Name(), filepath.Join(screenStateDir, unattendedMarkFile))
	}
	if s.Family() != graphical.FamilyKDE {
		return withCode(codeScreenUnsupported, fmt.Errorf("%s has no unattended access: its dialog asks each new device", s.Describe()))
	}
	// The permission is only worth anything if the portal knows the agent
	// by its app id, which takes its desktop entry.
	ensureDesktopEntry()
	if _, err := bus.Call(ctx, permStoreName, permStorePath, permStoreIface, "SetPermission", "sbssas",
		kdeAuthTable, true, kdeAuthID, portalAppID(), []string{"yes"}); err != nil {
		return withCode(codeScreenCapture, fmt.Errorf("the permission store: %w", err))
	}
	return nil
}

// clearUnattended takes the agent's pre-authorization away (only its own
// app id: other programs keep theirs).
func clearUnattended(ctx context.Context, bus *dbus.Conn, s graphical.Session) error {
	if s.Family() == graphical.FamilyGNOME && screenStateDir != "" {
		return ignoreMissing(os.Remove(filepath.Join(screenStateDir, unattendedMarkFile)))
	}
	if s.Family() != graphical.FamilyKDE {
		return nil
	}
	on, err := kdeAuthorized(ctx, bus)
	if err != nil || !on {
		return nil
	}
	if _, err := bus.Call(ctx, permStoreName, permStorePath, permStoreIface, "DeletePermission", "sss",
		kdeAuthTable, kdeAuthID, portalAppID()); err != nil {
		return withCode(codeScreenCapture, fmt.Errorf("the permission store: %w", err))
	}
	return nil
}

// kdeAuthorized tells whether the agent's app id has "yes" in KDE's table.
func kdeAuthorized(ctx context.Context, bus *dbus.Conn) (bool, error) {
	perms, err := lookupPermissions(ctx, bus, kdeAuthTable, kdeAuthID)
	if err != nil {
		return false, err
	}
	for _, p := range perms[portalAppID()] {
		if p == "yes" {
			return true, nil
		}
	}
	return false, nil
}

// lookupPermissions reads one entry of the permission store: app id → its
// permissions. A missing table or entry is empty.
func lookupPermissions(ctx context.Context, bus *dbus.Conn, table, id string) (map[string][]string, error) {
	reply, err := bus.Call(ctx, permStoreName, permStorePath, permStoreIface, "Lookup", "ss", table, id)
	var e *dbus.Error
	if errors.As(err, &e) && strings.HasSuffix(e.Name, ".NotFound") {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	out := map[string][]string{}
	if len(reply.Body) > 0 {
		m, _ := reply.Body[0].(map[string]any)
		for app, v := range m {
			list, _ := v.([]any)
			for _, p := range list {
				if s, ok := p.(string); ok {
					out[app] = append(out[app], s)
				}
			}
		}
	}
	return out, nil
}

// forgetPortalSession deletes the portal's remembered session for tok, if it
// is the agent's: the next session shows the dialog again.
func forgetPortalSession(ctx context.Context, bus *dbus.Conn, tok string) error {
	perms, err := lookupPermissions(ctx, bus, portalTable, tok)
	if err != nil || perms == nil {
		return nil
	}
	if _, ours := perms[portalAppID()]; !ours && len(perms) > 0 {
		if _, anon := perms[""]; !anon {
			return nil
		}
	}
	_, err = bus.Call(ctx, permStoreName, permStorePath, permStoreIface, "Delete", "ss", portalTable, tok)
	var e *dbus.Error
	if errors.As(err, &e) && strings.HasSuffix(e.Name, ".NotFound") {
		return nil
	}
	return err
}

// readTokenFile is the restore token saved as name in dir, "" if none.
func readTokenFile(dir, name string) string {
	data, err := os.ReadFile(filepath.Join(dir, name))
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(data))
}

// dialUserPipeWire connects to the user's own PipeWire (Mutter's streams
// are there, not behind a portal) and returns the socket.
func dialUserPipeWire(s graphical.Session) (int, error) {
	name := os.Getenv("PIPEWIRE_REMOTE")
	if name == "" || !filepath.IsAbs(name) {
		name = filepath.Join(s.RuntimeDir, "pipewire-0")
	}
	if s.RuntimeDir == "" && !filepath.IsAbs(name) {
		return -1, errors.New("no runtime dir for PipeWire's socket")
	}
	fd, err := unix.Socket(unix.AF_UNIX, unix.SOCK_STREAM|unix.SOCK_CLOEXEC, 0)
	if err != nil {
		return -1, err
	}
	if err := unix.Connect(fd, &unix.SockaddrUnix{Name: name}); err != nil {
		unix.Close(fd)
		return -1, fmt.Errorf("PipeWire at %s: %w", name, err)
	}
	return fd, nil
}
