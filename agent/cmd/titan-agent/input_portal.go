//go:build linux

package main

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/dbus"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/graphical"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/inject"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/portal"
)

// portalInputTokenFile keeps the mouse pad's own restore token: its portal
// session has no screen, so the remote desktop's token does not fit it.
const portalInputTokenFile = "portal-input-restore-token"

// portalInputWay serves a mouse pad (--input) through the RemoteDesktop
// portal (KDE, GNOME; ADR-0031 §2): pointer and keyboard only, with the
// desktop's dialog the first time. It returns errNoWay where there is no
// such portal.
func portalInputWay(stateDir string) func(graphical.Session) error {
	return func(s graphical.Session) error {
		addr := sessionBus(s)
		if addr == "" {
			return errNoWay
		}
		bus, err := dbus.Dial(addr)
		if err != nil {
			return errNoWay
		}
		defer bus.Close()
		ctx, cancel := context.WithTimeout(context.Background(), portalConsentTimeout+time.Minute)
		screenStateDir = stateDir
		started := time.Now()
		if s.Family() == graphical.FamilyGNOME && gnomeUnattended() {
			// GNOME with the unattended access: Mutter's own API, no dialog.
			m, err := portal.OpenMutter(ctx, bus, true)
			cancel()
			if err != nil {
				return withCode(codeInputUnsupported, err)
			}
			defer m.Close()
			inject.WarmUp(m, started)
			var mu sync.Mutex
			return inject.Serve(stdio(), inject.NewPortal(m, noScreen, 1), &mu)
		}
		ensureDesktopEntry()
		sess, err := portal.Open(ctx, bus, portal.Options{
			AppID:          portalAppID(),
			RestoreToken:   readTokenFile(stateDir, portalInputTokenFile),
			ConsentTimeout: portalConsentTimeout,
			InputOnly:      true,
		})
		cancel()
		switch {
		case errors.Is(err, portal.ErrUnavailable):
			return errNoWay
		case errors.Is(err, portal.ErrRefused):
			return withCode(codeScreenConsent, err)
		case err != nil:
			return withCode(codeInputUnsupported, err)
		}
		defer sess.Close()
		if sess.RestoreToken != "" {
			writeTokenFile(stateDir, portalInputTokenFile, sess.RestoreToken)
		}
		inject.WarmUp(sess, started)
		inj := inject.NewPortal(sess, noScreen, 1)
		if s.Family() == graphical.FamilyKDE {
			inj.SmoothScroll = sess.PointerAxis
		}
		var mu sync.Mutex
		return inject.Serve(stdio(), inj, &mu)
	}
}

// noScreen is the locator of a session without screen: absolute moves need a
// screen stream, and the mouse pad only sends relative ones.
func noScreen(int, int) (uint32, float64, float64, bool) { return 0, 0, 0, false }

// writeTokenFile saves a restore token as name in dir (tokens are
// single-use: each session gives the next one). A failure only means the
// desktop asks again next time.
func writeTokenFile(dir, name, tok string) {
	tmp, err := createPrivateTemp(dir, ".portal-*")
	if err != nil {
		return
	}
	defer os.Remove(tmp.Name())
	_, err = tmp.WriteString(tok + "\n")
	if cerr := tmp.Close(); err != nil || cerr != nil {
		return
	}
	_ = renameRetrying(tmp.Name(), filepath.Join(dir, name))
}
