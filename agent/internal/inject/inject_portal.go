//go:build linux

package inject

import (
	"errors"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/protocol"
)

// PortalDevice is the input side of a RemoteDesktop portal session (a
// *portal.Session): pointer in logical pixels, evdev buttons and keysyms.
type PortalDevice interface {
	PointerMotion(dx, dy float64) error
	PointerMotionAbsolute(stream uint32, x, y float64) error
	PointerButton(code int32, pressed bool) error
	PointerAxisDiscrete(axis uint32, steps int32) error
	KeyboardKeysym(sym int32, pressed bool) error
}

// PortalLocator places a point of the captured screen (all monitors, in
// pixels) on a stream, in that stream's logical pixels; ok is false between
// monitors.
type PortalLocator func(x, y int) (stream uint32, lx, ly float64, ok bool)

// Portal injects input through the RemoteDesktop portal (KDE, GNOME;
// ADR-0031 §2). Text goes as keysyms, so the desktop types the characters
// whatever its layout, as far as its keymap can.
type Portal struct {
	dev    PortalDevice
	locate PortalLocator
	// scale turns the client's relative motion (captured pixels) into
	// logical pixels.
	scale            float64
	scrollX, scrollY int
}

// NewPortal returns an injector on dev. scale is captured pixels per
// logical pixel (2 on a 200% monitor).
func NewPortal(dev PortalDevice, locate PortalLocator, scale float64) *Portal {
	if scale <= 0 {
		scale = 1
	}
	return &Portal{dev: dev, locate: locate, scale: scale}
}

// Move moves the pointer by dx, dy captured pixels.
func (p *Portal) Move(dx, dy int) error {
	return p.dev.PointerMotion(float64(dx)/p.scale, float64(dy)/p.scale)
}

// MoveTo puts the pointer at x, y of the captured screen; between two
// monitors it stays where it is.
func (p *Portal) MoveTo(x, y int) error {
	stream, lx, ly, ok := p.locate(x, y)
	if !ok {
		return nil
	}
	return p.dev.PointerMotionAbsolute(stream, lx, ly)
}

// Button presses or releases a protocol button.
func (p *Portal) Button(button uint8, down bool) error {
	code := map[uint8]int32{protocol.ButtonLeft: btnLeft, protocol.ButtonRight: btnRight, protocol.ButtonMiddle: btnMiddle}[button]
	if code == 0 {
		return nil // a button from a newer client
	}
	return p.dev.PointerButton(code, down)
}

// Scroll scrolls whole notches (dy > 0 up, dx > 0 right), keeping the
// remainder for the next call.
func (p *Portal) Scroll(dx, dy int) error {
	p.scrollY += dy
	p.scrollX += dx
	var err error
	if n := p.scrollY / 120; n != 0 {
		p.scrollY -= n * 120
		// The portal's vertical steps go down when positive.
		err = p.dev.PointerAxisDiscrete(0, int32(-n))
	}
	if n := p.scrollX / 120; n != 0 {
		p.scrollX -= n * 120
		err = errors.Join(err, p.dev.PointerAxisDiscrete(1, int32(n)))
	}
	return err
}

func (p *Portal) tap(sym uint32) error {
	return errors.Join(p.dev.KeyboardKeysym(int32(sym), true), p.dev.KeyboardKeysym(int32(sym), false))
}

// Text types s character by character: line breaks and tabs as the Return
// and Tab keys.
func (p *Portal) Text(s string) error {
	for _, r := range s {
		var sym uint32
		switch {
		case r == '\r':
			continue
		case r == '\n':
			sym = keysymReturn
		case r == '\t':
			sym = keysymTab
		default:
			sym = runeKeysym(r)
		}
		if err := p.tap(sym); err != nil {
			return err
		}
	}
	return nil
}

// Key presses or releases a protocol key; with ActionPress the modifiers in
// mods are held around it.
func (p *Portal) Key(key uint16, mods uint8, action uint8) error {
	sym, ok := keyKeysym(key)
	if !ok {
		return nil // a key from a newer client
	}
	switch action {
	case protocol.ActionDown:
		return p.dev.KeyboardKeysym(int32(sym), true)
	case protocol.ActionUp:
		return p.dev.KeyboardKeysym(int32(sym), false)
	}
	var held []uint32
	var err error
	for _, m := range []struct {
		bit uint8
		sym uint32
	}{{protocol.ModCtrl, keysymControlL}, {protocol.ModAlt, keysymAltL}, {protocol.ModShift, keysymShiftL}, {protocol.ModMeta, keysymSuperL}} {
		if mods&m.bit != 0 {
			err = errors.Join(err, p.dev.KeyboardKeysym(int32(m.sym), true))
			held = append(held, m.sym)
		}
	}
	err = errors.Join(err, p.tap(sym))
	for i := len(held) - 1; i >= 0; i-- {
		err = errors.Join(err, p.dev.KeyboardKeysym(int32(held[i]), false))
	}
	return err
}

// Blocked is always false: a locked Wayland session still gets the portal's
// input (the lock screen takes it), as on X11.
func (p *Portal) Blocked() bool { return false }
