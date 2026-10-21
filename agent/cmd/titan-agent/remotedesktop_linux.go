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

// queryRemoteDesktop reports the remote desktop's consent; nil when this
// agent does not serve it or the user has no graphical session.
func queryRemoteDesktop(stateDir string) *remoteDesktopReport {
	if !screenServed {
		return nil
	}
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
	r.Remembered = readTokenFile(stateDir) != ""
	r.UnattendedAvailable = s.Family() == graphical.FamilyKDE
	if r.UnattendedAvailable {
		if bus, err := dbus.Dial(sessionBus(s)); err == nil {
			defer bus.Close()
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			defer cancel()
			r.Unattended, _ = kdeAuthorized(ctx, bus)
		}
	}
	return r
}

// runRemoteDesktop is --remote-desktop <action>.
func runRemoteDesktop(stateDir, action string) error {
	if !screenServed {
		return withCode(codeScreenUnsupported, errors.New("this agent does not serve the remote desktop: it is only in titan-ssh canary"))
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
	switch action {
	case rdUnattendedOn:
		return setUnattended(ctx, bus, s)
	case rdUnattendedOff:
		return clearUnattended(ctx, bus, s)
	case rdForget:
		var errs []error
		if s.Family() == graphical.FamilyKDE {
			errs = append(errs, clearUnattended(ctx, bus, s))
		}
		if tok := readTokenFile(stateDir); tok != "" {
			errs = append(errs, forgetPortalSession(ctx, bus, tok))
			errs = append(errs, ignoreMissing(os.Remove(filepath.Join(stateDir, portalTokenFile))))
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

// setUnattended pre-authorizes the agent's app id. Only KDE has a way.
func setUnattended(ctx context.Context, bus *dbus.Conn, s graphical.Session) error {
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

// readTokenFile is the restore token saved in dir, "" if none.
func readTokenFile(dir string) string {
	data, err := os.ReadFile(filepath.Join(dir, portalTokenFile))
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(data))
}
