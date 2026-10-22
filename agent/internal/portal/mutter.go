//go:build unix

package portal

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/dbus"
)

// Mutter's own remote desktop and screen cast API (GNOME), which
// gnome-remote-desktop uses: no dialog. The agent only takes this way when
// the user turned the unattended access on (ADR-0031 §3).
const (
	mutterRDName    = "org.gnome.Mutter.RemoteDesktop"
	mutterRDPath    = dbus.ObjectPath("/org/gnome/Mutter/RemoteDesktop")
	mutterRDIface   = "org.gnome.Mutter.RemoteDesktop"
	mutterRDSession = "org.gnome.Mutter.RemoteDesktop.Session"
	mutterSCName    = "org.gnome.Mutter.ScreenCast"
	mutterSCPath    = dbus.ObjectPath("/org/gnome/Mutter/ScreenCast")
	mutterSCIface   = "org.gnome.Mutter.ScreenCast"
	mutterSCSession = "org.gnome.Mutter.ScreenCast.Session"
	mutterSCStream  = "org.gnome.Mutter.ScreenCast.Stream"
	mutterDispName  = "org.gnome.Mutter.DisplayConfig"
	mutterDispPath  = dbus.ObjectPath("/org/gnome/Mutter/DisplayConfig")
	mutterDispIface = "org.gnome.Mutter.DisplayConfig"
	// The cursor drawn into the frames: with it as metadata, Mutter 48
	// records a cursor-only frame on every repaint and then skips the real
	// one as "too early", so no image ever comes.
	mutterCursorEmbedded = 1
)

// MutterAvailable tells whether Mutter's remote desktop API answers on conn.
func MutterAvailable(ctx context.Context, conn *dbus.Conn) bool {
	r, err := conn.Call(ctx, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "NameHasOwner", "s", mutterRDName)
	if err != nil || len(r.Body) != 1 {
		return false
	}
	has, _ := r.Body[0].(bool)
	return has
}

// MutterSession is a remote desktop session of Mutter's own API: the
// screen of every monitor and pointer and keyboard, without a dialog.
type MutterSession struct {
	conn   *dbus.Conn
	rd     dbus.ObjectPath
	sc     dbus.ObjectPath
	paths  map[uint32]dbus.ObjectPath // stream node id → stream object, for absolute motion
	closed chan struct{}
	// Streams are the monitors, as the portal's.
	Streams []Stream
}

// OpenMutter starts a session of Mutter's API with every monitor (or only
// input when inputOnly).
func OpenMutter(ctx context.Context, conn *dbus.Conn, inputOnly bool) (*MutterSession, error) {
	if !MutterAvailable(ctx, conn) {
		return nil, ErrUnavailable
	}
	r, err := conn.Call(ctx, mutterRDName, mutterRDPath, mutterRDIface, "CreateSession", "")
	if err != nil {
		return nil, fmt.Errorf("mutter: CreateSession: %w", err)
	}
	m := &MutterSession{conn: conn, paths: map[uint32]dbus.ObjectPath{}, closed: make(chan struct{})}
	m.rd, _ = r.Body[0].(dbus.ObjectPath)
	ok := false
	defer func() {
		if !ok {
			m.Close()
		}
	}()
	if !inputOnly {
		if err := m.screenCast(ctx); err != nil {
			return nil, err
		}
	}
	added, stop, err := conn.Watch(ctx, mutterSCName, "", mutterSCStream, "PipeWireStreamAdded")
	if err != nil {
		return nil, err
	}
	defer stop()
	var streamPaths []dbus.ObjectPath
	if !inputOnly {
		connectors, err := m.connectors(ctx)
		if err != nil {
			return nil, err
		}
		for _, c := range connectors {
			r, err := conn.Call(ctx, mutterSCName, m.sc, mutterSCSession, "RecordMonitor", "sa{sv}", c,
				map[string]dbus.Variant{"cursor-mode": dbus.MakeVariant(uint32(mutterCursorEmbedded))})
			if err != nil {
				return nil, fmt.Errorf("mutter: RecordMonitor %s: %w", c, err)
			}
			p, _ := r.Body[0].(dbus.ObjectPath)
			streamPaths = append(streamPaths, p)
		}
	}
	if _, err := conn.Call(ctx, mutterRDName, m.rd, mutterRDSession, "Start", ""); err != nil {
		return nil, fmt.Errorf("mutter: Start: %w", err)
	}
	nodes := map[dbus.ObjectPath]uint32{}
	timeout := time.After(10 * time.Second)
	for len(nodes) < len(streamPaths) {
		select {
		case sig, open := <-added:
			if !open {
				return nil, dbus.ErrClosed
			}
			if len(sig.Body) == 1 {
				n, _ := sig.Body[0].(uint32)
				nodes[sig.Path] = n
			}
		case <-timeout:
			return nil, errors.New("mutter: the screen streams did not start")
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	for _, p := range streamPaths {
		st := Stream{Node: nodes[p]}
		if r, err := conn.Call(ctx, mutterSCName, p, ifaceProps, "Get", "ss", mutterSCStream, "Parameters"); err == nil && len(r.Body) == 1 {
			if v, ok := r.Body[0].(dbus.Variant); ok {
				params, _ := v.Value.(map[string]any)
				if pv, ok := params["position"].(dbus.Variant); ok {
					st.X, st.Y = pairInts(pv.Value)
				}
				if sv, ok := params["size"].(dbus.Variant); ok {
					st.Width, st.Height = pairInts(sv.Value)
				}
				if ov, ok := params["output-name"].(dbus.Variant); ok {
					st.ID, _ = ov.Value.(string)
				}
			}
		}
		m.paths[st.Node] = p
		m.Streams = append(m.Streams, st)
	}
	go m.watchClosed()
	ok = true
	return m, nil
}

func (m *MutterSession) screenCast(ctx context.Context) error {
	r, err := m.conn.Call(ctx, mutterRDName, m.rd, ifaceProps, "Get", "ss", mutterRDSession, "SessionId")
	if err != nil {
		return fmt.Errorf("mutter: SessionId: %w", err)
	}
	v, _ := r.Body[0].(dbus.Variant)
	id, _ := v.Value.(string)
	r, err = m.conn.Call(ctx, mutterSCName, mutterSCPath, mutterSCIface, "CreateSession", "a{sv}",
		map[string]dbus.Variant{"remote-desktop-session-id": dbus.MakeVariant(id)})
	if err != nil {
		return fmt.Errorf("mutter: ScreenCast.CreateSession: %w", err)
	}
	m.sc, _ = r.Body[0].(dbus.ObjectPath)
	return nil
}

// connectors lists the connectors of the monitors in use (DisplayConfig).
func (m *MutterSession) connectors(ctx context.Context) ([]string, error) {
	r, err := m.conn.Call(ctx, mutterDispName, mutterDispPath, mutterDispIface, "GetCurrentState", "")
	if err != nil {
		return nil, fmt.Errorf("mutter: GetCurrentState: %w", err)
	}
	if len(r.Body) < 3 {
		return nil, errors.New("mutter: GetCurrentState: malformed reply")
	}
	// Logical monitors: a(iiduba(ssss)a{sv}); their monitors' first field is
	// the connector. Only monitors in a logical monitor are on.
	logical, _ := r.Body[2].([]any)
	var out []string
	for _, l := range logical {
		lm, _ := l.([]any)
		if len(lm) < 6 {
			continue
		}
		mons, _ := lm[5].([]any)
		for _, mon := range mons {
			spec, _ := mon.([]any)
			if len(spec) > 0 {
				if c, ok := spec[0].(string); ok {
					out = append(out, c)
				}
			}
		}
	}
	if len(out) == 0 {
		return nil, errors.New("mutter: no monitor is on")
	}
	return out, nil
}

func (m *MutterSession) watchClosed() {
	ctx := context.Background()
	ch, stop, err := m.conn.Watch(ctx, mutterRDName, m.rd, mutterRDSession, "Closed")
	if err != nil {
		return
	}
	defer stop()
	select {
	case <-ch:
	case <-m.conn.Done():
	}
	select {
	case <-m.closed:
	default:
		close(m.closed)
	}
}

// Close stops the session.
func (m *MutterSession) Close() {
	if m.rd != "" {
		_ = m.conn.Send(mutterRDName, m.rd, mutterRDSession, "Stop", "")
	}
}

func (m *MutterSession) notify(method, sig string, args ...any) error {
	return m.conn.Send(mutterRDName, m.rd, mutterRDSession, method, sig, args...)
}

// The input methods mirror Session's, so both serve inject.PortalDevice.

func (m *MutterSession) PointerMotion(dx, dy float64) error {
	return m.notify("NotifyPointerMotionRelative", "dd", dx, dy)
}

func (m *MutterSession) PointerMotionAbsolute(stream uint32, x, y float64) error {
	p, ok := m.paths[stream]
	if !ok {
		return nil
	}
	return m.notify("NotifyPointerMotionAbsolute", "sdd", string(p), x, y)
}

func (m *MutterSession) PointerButton(code int32, pressed bool) error {
	return m.notify("NotifyPointerButton", "ib", code, pressed)
}

func (m *MutterSession) PointerAxisDiscrete(axis uint32, steps int32) error {
	return m.notify("NotifyPointerAxisDiscrete", "ui", axis, steps)
}

func (m *MutterSession) KeyboardKeysym(sym int32, pressed bool) error {
	return m.notify("NotifyKeyboardKeysym", "ub", uint32(sym), pressed)
}
