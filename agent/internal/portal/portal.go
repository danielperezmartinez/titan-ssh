//go:build unix

// Package portal drives the desktop's RemoteDesktop and ScreenCast portals
// (xdg-desktop-portal) over the user's session bus (ADR-0031 §2, §3): one
// session that gives the screen as PipeWire streams and takes pointer and
// keyboard input, with the consent dialog remembered through a restore token.
package portal

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/dbus"
)

const (
	busName      = "org.freedesktop.portal.Desktop"
	objectPath   = dbus.ObjectPath("/org/freedesktop/portal/desktop")
	ifaceRemote  = "org.freedesktop.portal.RemoteDesktop"
	ifaceCast    = "org.freedesktop.portal.ScreenCast"
	ifaceRequest = "org.freedesktop.portal.Request"
	ifaceSession = "org.freedesktop.portal.Session"
	ifaceProps   = "org.freedesktop.DBus.Properties"
	ifaceReg     = "org.freedesktop.host.portal.Registry"
)

// Device types of SelectDevices.
const (
	DeviceKeyboard = 1
	DevicePointer  = 2
)

// Cursor modes of SelectSources.
const (
	CursorHidden   = 1
	CursorEmbedded = 2
	CursorMetadata = 4
)

// persistUntilRevoked is persist_mode 2: the permission lasts until the user
// revokes it.
const persistUntilRevoked = 2

// ErrUnavailable reports a desktop without a RemoteDesktop portal (no
// xdg-desktop-portal, or a backend without it, as on wlroots).
var ErrUnavailable = errors.New("portal: the desktop has no RemoteDesktop portal")

// ErrRefused reports that the consent dialog was refused or not answered.
var ErrRefused = errors.New("portal: the remote desktop was not allowed on the destination's screen")

// Stream is one monitor of the session, as Start describes it.
type Stream struct {
	// Node is the PipeWire node id of the stream.
	Node uint32
	// X, Y, Width and Height place the monitor in the desktop, in logical
	// (scaled) pixels; zero when the backend does not say.
	X, Y, Width, Height int
	// ID is the backend's identifier of the monitor, "" if it gives none.
	ID string
}

// Options configure Open.
type Options struct {
	// AppID is registered with the host Registry (where it exists) so the
	// permission is kept under this name; "" skips it.
	AppID string
	// RestoreToken is the token a previous session returned, to skip the
	// dialog.
	RestoreToken string
	// ConsentTimeout bounds how long the dialog may wait for an answer.
	ConsentTimeout time.Duration
	// EmbeddedCursor asks for the cursor drawn into the frames even where the
	// backend offers it as metadata (GNOME: see mutterCursorEmbedded).
	EmbeddedCursor bool
	// InputOnly asks for pointer and keyboard without the screen (a mouse
	// pad): no ScreenCast, so only relative pointer motion.
	InputOnly bool
}

// Session is an open remote desktop session.
type Session struct {
	conn   *dbus.Conn
	handle dbus.ObjectPath
	// Streams are the monitors shared, in the order Start gave them.
	Streams []Stream
	// RestoreToken replaces the one passed to Open: tokens are single-use.
	RestoreToken string
	// Devices are the input devices granted (Device* bits).
	Devices uint32
	// CursorMode is the cursor mode in use (Cursor*).
	CursorMode uint32
	// Version is the RemoteDesktop portal's version.
	Version uint32
	// Registered tells whether the portal took the app id. It refuses one
	// without an installed .desktop file, and older portals have no
	// Registry: the permission then goes under the empty app id.
	Registered bool
}

// Open starts a remote desktop session on the bus conn: pointer and
// keyboard, every monitor, the cursor as metadata where the backend can (or
// embedded in the image), persisted until revoked. The first time, the
// desktop asks its user; with a valid restore token it does not.
func Open(ctx context.Context, conn *dbus.Conn, opt Options) (*Session, error) {
	version, err := propertyU32(ctx, conn, ifaceRemote, "version")
	if err != nil {
		return nil, unavailable(err)
	}
	s := &Session{conn: conn, Version: version}
	if opt.AppID != "" {
		s.Registered = register(ctx, conn, opt.AppID) == nil
	}
	if opt.ConsentTimeout <= 0 {
		opt.ConsentTimeout = 2 * time.Minute
	}

	if !opt.InputOnly {
		modes, err := propertyU32(ctx, conn, ifaceCast, "AvailableCursorModes")
		if err != nil {
			return nil, unavailable(err)
		}
		s.CursorMode = CursorEmbedded
		if modes&CursorMetadata != 0 && !(opt.EmbeddedCursor && modes&CursorEmbedded != 0) {
			s.CursorMode = CursorMetadata
		} else if modes&CursorEmbedded == 0 {
			s.CursorMode = CursorHidden
		}
	}

	res, err := s.request(ctx, opt.ConsentTimeout, ifaceRemote, "CreateSession", "a{sv}", map[string]dbus.Variant{
		"session_handle_token": dbus.MakeVariant(token()),
	})
	if err != nil {
		return nil, fmt.Errorf("portal: CreateSession: %w", err)
	}
	switch h := res["session_handle"].(type) {
	case dbus.Variant:
		switch v := h.Value.(type) {
		case string:
			s.handle = dbus.ObjectPath(v)
		case dbus.ObjectPath:
			s.handle = v
		}
	}
	if s.handle == "" {
		return nil, errors.New("portal: CreateSession returned no session")
	}
	ok := false
	defer func() {
		if !ok {
			s.Close()
		}
	}()

	devOpts := map[string]dbus.Variant{"types": dbus.MakeVariant(uint32(DeviceKeyboard | DevicePointer))}
	if version >= 2 {
		devOpts["persist_mode"] = dbus.MakeVariant(uint32(persistUntilRevoked))
		if opt.RestoreToken != "" {
			devOpts["restore_token"] = dbus.MakeVariant(opt.RestoreToken)
		}
	}
	if _, err := s.request(ctx, opt.ConsentTimeout, ifaceRemote, "SelectDevices", "oa{sv}", s.handle, devOpts); err != nil {
		return nil, fmt.Errorf("portal: SelectDevices: %w", err)
	}
	if !opt.InputOnly {
		if _, err := s.request(ctx, opt.ConsentTimeout, ifaceCast, "SelectSources", "oa{sv}", s.handle, map[string]dbus.Variant{
			"types":       dbus.MakeVariant(uint32(1)), // monitors
			"multiple":    dbus.MakeVariant(true),
			"cursor_mode": dbus.MakeVariant(s.CursorMode),
		}); err != nil {
			return nil, fmt.Errorf("portal: SelectSources: %w", err)
		}
	}
	res, err = s.request(ctx, opt.ConsentTimeout, ifaceRemote, "Start", "osa{sv}", s.handle, "", map[string]dbus.Variant{})
	if err != nil {
		return nil, fmt.Errorf("portal: Start: %w", err)
	}
	devicesKnown := false
	if v, ok := res["devices"].(dbus.Variant); ok {
		s.Devices, devicesKnown = v.Value.(uint32)
	}
	if v, ok := res["restore_token"].(dbus.Variant); ok {
		s.RestoreToken, _ = v.Value.(string)
	}
	if v, ok := res["streams"].(dbus.Variant); ok {
		s.Streams = parseStreams(v.Value)
	}
	if len(s.Streams) == 0 && !opt.InputOnly {
		return nil, errors.New("portal: the session has no screen")
	}
	// GNOME's dialog shares the screen alone unless its user turns "allow
	// remote interaction" on: a remote desktop then only shows, and a mouse
	// pad has nothing to do.
	if opt.InputOnly && devicesKnown && s.Devices&DevicePointer == 0 {
		return nil, fmt.Errorf("%w: the remote interaction was not allowed", ErrRefused)
	}
	ok = true
	return s, nil
}

// parseStreams reads Start's a(ua{sv}).
func parseStreams(v any) []Stream {
	list, _ := v.([]any)
	var out []Stream
	for _, item := range list {
		pair, _ := item.([]any)
		if len(pair) != 2 {
			continue
		}
		node, _ := pair[0].(uint32)
		props, _ := pair[1].(map[string]any)
		st := Stream{Node: node}
		if p, ok := props["position"].(dbus.Variant); ok {
			st.X, st.Y = pairInts(p.Value)
		}
		if p, ok := props["size"].(dbus.Variant); ok {
			st.Width, st.Height = pairInts(p.Value)
		}
		if p, ok := props["id"].(dbus.Variant); ok {
			st.ID, _ = p.Value.(string)
		}
		out = append(out, st)
	}
	return out
}

func pairInts(v any) (int, int) {
	p, _ := v.([]any)
	if len(p) != 2 {
		return 0, 0
	}
	a, _ := p[0].(int32)
	b, _ := p[1].(int32)
	return int(a), int(b)
}

// unavailable maps the bus's answer to a missing portal onto ErrUnavailable.
func unavailable(err error) error {
	var e *dbus.Error
	if errors.As(err, &e) {
		return fmt.Errorf("%w (%s)", ErrUnavailable, e.Name)
	}
	return err
}

// register identifies the agent to the portal by app id, before any other
// portal call on this connection.
func register(ctx context.Context, conn *dbus.Conn, appID string) error {
	_, err := conn.Call(ctx, busName, objectPath, ifaceReg, "Register", "sa{sv}", appID, map[string]dbus.Variant{})
	if err != nil {
		return fmt.Errorf("portal: Registry.Register: %w", err)
	}
	return nil
}

// propertyU32 reads a uint32 property of the portal object.
func propertyU32(ctx context.Context, conn *dbus.Conn, iface, name string) (uint32, error) {
	reply, err := conn.Call(ctx, busName, objectPath, ifaceProps, "Get", "ss", iface, name)
	if err != nil {
		return 0, err
	}
	if len(reply.Body) == 1 {
		if v, ok := reply.Body[0].(dbus.Variant); ok {
			if n, ok := v.Value.(uint32); ok {
				return n, nil
			}
		}
	}
	return 0, fmt.Errorf("portal: %s.%s is not a uint32", iface, name)
}

// request calls a portal method that answers through a Request object, and
// waits for its Response. A refusal (or no answer within timeout) is
// ErrRefused.
func (s *Session) request(ctx context.Context, timeout time.Duration, iface, method, sig string, args ...any) (map[string]any, error) {
	tok := token()
	path := dbus.ObjectPath("/org/freedesktop/portal/desktop/request/" + senderPart(s.conn.UniqueName()) + "/" + tok)
	ch, stop, err := s.conn.Watch(ctx, busName, path, ifaceRequest, "Response")
	if err != nil {
		return nil, err
	}
	defer stop()

	// The options a{sv} is always the last argument: add the handle token.
	last := args[len(args)-1].(map[string]dbus.Variant)
	last["handle_token"] = dbus.MakeVariant(tok)
	reply, err := s.conn.Call(ctx, busName, objectPath, iface, method, sig, args...)
	if err != nil {
		var e *dbus.Error
		if errors.As(err, &e) && strings.HasSuffix(e.Name, ".AccessDenied") {
			return nil, fmt.Errorf("%w: %v", ErrRefused, e)
		}
		return nil, err
	}
	// Old portals ignore handle_token; the reply names the real request.
	if len(reply.Body) == 1 {
		if p, ok := reply.Body[0].(dbus.ObjectPath); ok && p != path {
			stop()
			if ch, stop, err = s.conn.Watch(ctx, busName, p, ifaceRequest, "Response"); err != nil {
				return nil, err
			}
			defer stop()
			path = p
		}
	}

	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case m, open := <-ch:
		if !open {
			return nil, dbus.ErrClosed
		}
		if len(m.Body) != 2 {
			return nil, errors.New("portal: malformed Response")
		}
		code, _ := m.Body[0].(uint32)
		results, _ := m.Body[1].(map[string]any)
		switch code {
		case 0:
			return results, nil
		case 1:
			return nil, ErrRefused
		default:
			return nil, fmt.Errorf("portal: %s.%s ended without an answer (response %d)", iface, method, code)
		}
	case <-timer.C:
		_ = s.conn.Send(busName, path, ifaceRequest, "Close", "")
		return nil, fmt.Errorf("%w: nobody answered the dialog within %s", ErrRefused, timeout)
	case <-ctx.Done():
		_ = s.conn.Send(busName, path, ifaceRequest, "Close", "")
		return nil, ctx.Err()
	}
}

// senderPart is the unique name as request paths spell it (":1.42" → "1_42").
func senderPart(unique string) string {
	return strings.ReplaceAll(strings.TrimPrefix(unique, ":"), ".", "_")
}

func token() string {
	var b [8]byte
	_, _ = rand.Read(b[:])
	return "titan_" + hex.EncodeToString(b[:])
}

// OpenPipeWireRemote returns a PipeWire connection descriptor that can see
// only this session's streams. The caller owns it.
func (s *Session) OpenPipeWireRemote(ctx context.Context) (int, error) {
	reply, err := s.conn.Call(ctx, busName, objectPath, ifaceCast, "OpenPipeWireRemote", "oa{sv}", s.handle, map[string]dbus.Variant{})
	if err != nil {
		return -1, fmt.Errorf("portal: OpenPipeWireRemote: %w", err)
	}
	if len(reply.Body) != 1 || len(reply.Files) == 0 {
		for _, fd := range reply.Files {
			closeFD(fd)
		}
		return -1, errors.New("portal: OpenPipeWireRemote returned no descriptor")
	}
	idx, _ := reply.Body[0].(dbus.FDIndex)
	fd := -1
	for i, f := range reply.Files {
		if i == int(idx) {
			fd = f
		} else {
			closeFD(f)
		}
	}
	if fd < 0 {
		return -1, errors.New("portal: OpenPipeWireRemote returned a bad descriptor index")
	}
	return fd, nil
}

// Closed is closed when the desktop ends the session (the user stopped it
// from the desktop, or the backend went away) or the bus connection drops.
func (s *Session) Closed(ctx context.Context) (<-chan struct{}, error) {
	ch, stop, err := s.conn.Watch(ctx, busName, s.handle, ifaceSession, "Closed")
	if err != nil {
		return nil, err
	}
	out := make(chan struct{})
	go func() {
		defer stop()
		select {
		case <-ch:
		case <-s.conn.Done():
		}
		close(out)
	}()
	return out, nil
}

// Close ends the session.
func (s *Session) Close() {
	if s.handle != "" {
		_ = s.conn.Send(busName, s.handle, ifaceSession, "Close", "")
	}
}

// Input. The Notify methods have no reply worth waiting for: they go out as
// one-way calls, in order, so input is never held up by the bus.

func (s *Session) notify(method, sig string, args ...any) error {
	return s.conn.Send(busName, objectPath, ifaceRemote, method, sig, append([]any{s.handle, map[string]dbus.Variant{}}, args...)...)
}

// PointerMotion moves the pointer by dx, dy logical pixels.
func (s *Session) PointerMotion(dx, dy float64) error {
	return s.notify("NotifyPointerMotion", "oa{sv}dd", dx, dy)
}

// PointerMotionAbsolute puts the pointer at x, y of stream (its node id), in
// the stream's logical pixels.
func (s *Session) PointerMotionAbsolute(stream uint32, x, y float64) error {
	return s.notify("NotifyPointerMotionAbsolute", "oa{sv}udd", stream, x, y)
}

// PointerButton presses or releases an evdev button (BTN_LEFT 0x110…).
func (s *Session) PointerButton(code int32, pressed bool) error {
	return s.notify("NotifyPointerButton", "oa{sv}iu", code, state(pressed))
}

// PointerAxisDiscrete scrolls whole notches: axis 0 vertical (steps > 0
// down), 1 horizontal (steps > 0 right).
func (s *Session) PointerAxisDiscrete(axis uint32, steps int32) error {
	return s.notify("NotifyPointerAxisDiscrete", "oa{sv}ui", axis, steps)
}

// KeyboardKeysym presses or releases a keysym.
func (s *Session) KeyboardKeysym(sym int32, pressed bool) error {
	return s.notify("NotifyKeyboardKeysym", "oa{sv}iu", sym, state(pressed))
}

// KeyboardKeycode presses or releases an evdev key code.
func (s *Session) KeyboardKeycode(code int32, pressed bool) error {
	return s.notify("NotifyKeyboardKeycode", "oa{sv}iu", code, state(pressed))
}

func state(pressed bool) uint32 {
	if pressed {
		return 1
	}
	return 0
}
