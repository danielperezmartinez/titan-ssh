//go:build linux

package main

import (
	"context"
	"errors"
	"fmt"
	"image"
	"math"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/dbus"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/graphical"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/inject"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/pipewire"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/portal"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/screen"
)

// portalTokenFile keeps the portal's restore token in the state dir, so the
// desktop does not ask again (ADR-0031 §3).
const portalTokenFile = "portal-restore-token"

// stableAppID is the app's id; the canary's adds ".canary". The agent
// registers with the portal under it, so the desktop keeps the permission
// under the app's name.
const stableAppID = "io.github.danielperezmartinez.titanssh"

// portalAppID is the app id of this agent's app.
func portalAppID() string {
	if channel == "canary" {
		return stableAppID + ".canary"
	}
	return stableAppID
}

// Timeouts of the portal way: the user's answer to the dialog, and the first
// frame of each monitor once allowed.
var (
	portalConsentTimeout = 90 * time.Second
	portalFrameTimeout   = 10 * time.Second
)

// sessionBus is the address of the session's bus: its own, or the standard
// one under the runtime dir.
func sessionBus(s graphical.Session) string {
	if s.DBusAddress != "" {
		return s.DBusAddress
	}
	if s.RuntimeDir != "" {
		return "unix:path=" + filepath.Join(s.RuntimeDir, "bus")
	}
	return ""
}

// openPortalScreen serves a Wayland desktop through the RemoteDesktop and
// ScreenCast portals and PipeWire (KDE, GNOME; ADR-0031 §2).
func openPortalScreen(s graphical.Session) (*screenSource, error) {
	addr := sessionBus(s)
	if addr == "" {
		return nil, errNoWay
	}
	bus, err := dbus.Dial(addr)
	if err != nil {
		return nil, errNoWay
	}
	ok := false
	var closers []func()
	cleanup := func() {
		for i := len(closers) - 1; i >= 0; i-- {
			closers[i]()
		}
	}
	defer func() {
		if !ok {
			cleanup()
		}
	}()
	closers = append(closers, func() { bus.Close() })

	ctx, cancel := context.WithTimeout(context.Background(), portalConsentTimeout+time.Minute)
	defer cancel()
	if screenUnattended {
		// Best effort: without it the dialog shows, as by default.
		_ = setUnattended(ctx, bus, s)
	}
	started := time.Now()
	var (
		streams  []portal.Stream
		dev      inject.PortalDevice
		embedded bool
		fd       int
		smooth   func(dx, dy float64) error
	)
	if s.Family() == graphical.FamilyGNOME && gnomeUnattended() {
		// GNOME with the unattended access: Mutter's own API, no dialog.
		m, err := portal.OpenMutter(ctx, bus, false)
		if err != nil {
			return nil, withCode(codeScreenCapture, err)
		}
		closers = append(closers, m.Close)
		streams, dev, embedded = m.Streams, m, true
		if fd, err = dialUserPipeWire(s); err != nil {
			return nil, withCode(codeScreenCapture, err)
		}
	} else {
		ensureDesktopEntry()
		sess, err := portal.Open(ctx, bus, portal.Options{
			AppID:          portalAppID(),
			RestoreToken:   readPortalToken(),
			ConsentTimeout: portalConsentTimeout,
			EmbeddedCursor: s.Family() == graphical.FamilyGNOME,
		})
		switch {
		case errors.Is(err, portal.ErrUnavailable):
			return nil, errNoWay
		case errors.Is(err, portal.ErrRefused):
			return nil, withCode(codeScreenConsent, err)
		case err != nil:
			return nil, withCode(codeScreenCapture, err)
		}
		closers = append(closers, sess.Close)
		if sess.RestoreToken != "" {
			writePortalToken(sess.RestoreToken)
		}
		streams, dev, embedded = sess.Streams, sess, sess.CursorMode != portal.CursorMetadata
		if s.Family() == graphical.FamilyKDE {
			smooth = sess.PointerAxis
		}
		if fd, err = sess.OpenPipeWireRemote(ctx); err != nil {
			return nil, withCode(codeScreenCapture, err)
		}
	}
	if os.Getenv("TITAN_AGENT_DEBUG") != "" {
		pipewire.Debugf = func(format string, args ...any) { fmt.Fprintf(os.Stderr, format+"\n", args...) }
	}
	pw, err := pipewire.NewConn(ctx, fd, appName())
	if err != nil {
		return nil, withCode(codeScreenCapture, err)
	}
	closers = append(closers, func() { pw.Close() })

	comp := &screen.Composite{}
	var parts []*portalPart
	for i, st := range streams {
		stream, err := pw.Consume(ctx, st.Node, fmt.Sprintf("%s-screen-%d", appName(), i))
		if err != nil {
			return nil, withCode(codeScreenCapture, err)
		}
		closers = append(closers, stream.Close)
		if err := stream.WaitFrame(portalFrameTimeout); err != nil {
			return nil, withCode(codeScreenCapture, err)
		}
		p := &portalPart{stream: stream, node: st.Node, embedded: embedded}
		w, _ := stream.Size()
		p.scale = 1
		if st.Width > 0 && w > 0 {
			p.scale = float64(w) / float64(st.Width)
		}
		parts = append(parts, p)
		comp.Parts = append(comp.Parts, screen.Part{
			X: int(math.Round(float64(st.X) * p.scale)), Y: int(math.Round(float64(st.Y) * p.scale)),
			Capturer: p,
		})
	}

	locate := func(x, y int) (uint32, float64, float64, bool) {
		i, px, py, ok := comp.Locate(x, y)
		if !ok {
			return 0, 0, 0, false
		}
		p := parts[i]
		return p.node, float64(px) / p.scale, float64(py) / p.scale, true
	}
	inject.WarmUp(dev, started)
	var mu sync.Mutex
	inj := inject.NewPortal(dev, locate, parts[0].scale)
	inj.SmoothScroll = smooth
	inj.LayoutKeysyms = s.Family() == graphical.FamilyGNOME
	ok = true
	return &screenSource{
		capturer: comp,
		input:    inject.NewHandler(inj, &mu),
		close:    cleanup,
	}, nil
}

// portalPart is one monitor of a portal session: a PipeWire stream.
type portalPart struct {
	stream   *pipewire.Stream
	node     uint32
	scale    float64
	embedded bool // the cursor is drawn into the frames
	read     bool // the last Capture read a frame
}

func (p *portalPart) Size() (int, int, error) {
	if err := p.stream.Err(); err != nil {
		return 0, 0, withCode(codeScreenCapture, fmt.Errorf("the screen stream ended: %w", err))
	}
	w, h := p.stream.Size()
	return w, h, nil
}

// Capture reads the last frame; one of another size (the monitor just
// changed) leaves dst as it was, and the next Size gives the new one.
func (p *portalPart) Capture(dst []uint32, w, h int) error {
	p.read = p.stream.Read(dst, w, h)
	return nil
}

// Damage is what the compositor said changed (its VideoDamage metadata).
func (p *portalPart) Damage() ([]image.Rectangle, bool) {
	if !p.read {
		return nil, false
	}
	rects, ok := p.stream.Damage()
	if !ok {
		return nil, false
	}
	out := make([]image.Rectangle, len(rects))
	for i, r := range rects {
		out[i] = image.Rect(r.X, r.Y, r.X+r.W, r.Y+r.H)
	}
	return out, true
}

func (p *portalPart) Cursor() (screen.Cursor, error) {
	if p.embedded {
		return screen.Cursor{}, nil
	}
	c := p.stream.Cursor()
	return screen.Cursor{
		X: c.X, Y: c.Y, Visible: c.Visible, Serial: c.Serial,
		Width: c.Width, Height: c.Height, HotX: c.HotX, HotY: c.HotY, Pixels: c.Pixels,
	}, nil
}

// readPortalToken is the saved restore token, "" if there is none.
func readPortalToken() string {
	if screenStateDir == "" {
		return ""
	}
	return readTokenFile(screenStateDir, portalTokenFile)
}

// writePortalToken saves the token the portal returned.
func writePortalToken(tok string) {
	if screenStateDir != "" {
		writeTokenFile(screenStateDir, portalTokenFile, tok)
	}
}
