//go:build linux

package inject

import (
	"errors"
	"time"

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
	// SmoothScroll, when set, takes the scroll as it comes, fractions of a
	// notch included, in smoothUnitsPerNotch (dy > 0 up, dx > 0 right).
	// KDE needs it: its portal hands discrete steps to KWin as an axis value
	// of 1 each, a fifteenth of a real notch.
	SmoothScroll func(dx, dy float64) error
	// LayoutKeysyms types characters as keyboard layouts list them: an
	// accented letter as its dead key and the letter, € by its EuroSign
	// keysym. GNOME needs it: Mutter types only the keysyms of the layout's
	// keys, and á is no key's on a Spanish layout, but ´ and a are. KDE
	// types any keysym.
	LayoutKeysyms bool
}

// smoothUnitsPerNotch is one notch for SmoothScroll: Qt turns each unit of
// a continuous axis into 12 of angle, so 10 make the 120 of a wheel notch.
const smoothUnitsPerNotch = 10

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
	if p.SmoothScroll != nil {
		if dx == 0 && dy == 0 {
			return nil
		}
		return p.SmoothScroll(float64(dx)*smoothUnitsPerNotch/120, float64(dy)*smoothUnitsPerNotch/120)
	}
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
		var syms []uint32
		switch {
		case r == '\r':
			continue
		case r == '\n':
			syms = []uint32{keysymReturn}
		case r == '\t':
			syms = []uint32{keysymTab}
		default:
			if d, ok := deadKeyFor[r]; ok && p.LayoutKeysyms {
				syms = []uint32{d.dead, runeKeysym(d.base)}
			} else if sym, ok := legacyKeysyms[r]; ok && p.LayoutKeysyms {
				syms = []uint32{sym}
			} else {
				syms = []uint32{runeKeysym(r)}
			}
		}
		for _, sym := range syms {
			if err := p.tap(sym); err != nil {
				return err
			}
		}
	}
	return nil
}

// legacyKeysyms are characters outside Latin-1 that keyboard layouts list
// by an older keysym than their Unicode one (0x01000000 + code point).
var legacyKeysyms = map[rune]uint32{'€': 0x20ac}

// deadKey is an accented letter as a dead key's keysym and the bare letter.
type deadKey struct {
	dead uint32
	base rune
}

// deadKeyFor decomposes the accented letters of Western European layouts
// into the dead keys those layouts have.
var deadKeyFor = func() map[rune]deadKey {
	m := map[rune]deadKey{}
	for _, d := range []struct {
		dead    uint32
		letters string // pairs of bare letter and accented letter
	}{
		{0xfe51, "aáeéiíoóuúyýAÁEÉIÍOÓUÚYÝ"}, // dead_acute
		{0xfe50, "aàeèiìoòuùAÀEÈIÌOÒUÙ"},     // dead_grave
		{0xfe52, "aâeêiîoôuûAÂEÊIÎOÔUÛ"},     // dead_circumflex
		{0xfe57, "aäeëiïoöuüyÿAÄEËIÏOÖUÜ"},   // dead_diaeresis
		{0xfe53, "aãoõAÃOÕ"},                 // dead_tilde
	} {
		rs := []rune(d.letters)
		for i := 0; i+1 < len(rs); i += 2 {
			m[rs[i+1]] = deadKey{dead: d.dead, base: rs[i]}
		}
	}
	return m
}()

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

// WarmUp readies a remote desktop session's keyboard: the compositor drops
// the events of a new virtual device for a moment after the session starts,
// and the first key and pointer event after it (seen with Mutter 48, where
// the seat switches keymaps). It waits until settle has passed since started,
// then taps Shift and nudges the pointer there and back.
func WarmUp(dev PortalDevice, started time.Time) {
	if d := settle - time.Since(started); d > 0 {
		time.Sleep(d)
	}
	_ = dev.KeyboardKeysym(keysymShiftL, true)
	_ = dev.KeyboardKeysym(keysymShiftL, false)
	// The pointer's first event goes the same way: a nudge there and back.
	_ = dev.PointerMotion(1, 0)
	_ = dev.PointerMotion(-1, 0)
	time.Sleep(settle / 4)
}

// settle is how long a new session's input takes to be delivered.
const settle = 700 * time.Millisecond
