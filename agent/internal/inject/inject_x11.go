//go:build linux

package inject

import (
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/graphical"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/protocol"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/x11"
)

// New connects to the user's X11 session and returns its injector. A Wayland
// session has none yet (ADR-0016 §5, ADR-0029 §5).
func New() (Injector, error) {
	s, err := graphical.Find()
	if err != nil {
		return nil, err
	}
	if s.Kind != graphical.X11 {
		return nil, fmt.Errorf("%w: %s", ErrUnsupported, s.Describe())
	}
	d, err := x11.ParseDisplay(s.Display)
	if err != nil {
		return nil, err
	}
	x, err := x11.Dial(d, s.Xauthority)
	if err != nil {
		return nil, err
	}
	inj, err := NewX11(x)
	if err != nil {
		x.Close()
		return nil, err
	}
	return inj, nil
}

// X11 injects input into an X server through XTEST (ADR-0029). Characters the
// active layout lacks are typed by mapping them, for the moment, to a spare
// keycode, as xdotool does; Restore puts those keycodes back.
type X11 struct {
	x       *x11.Conn
	mapping x11.KeyboardMapping
	// xkb says whether the server has XKB, which tells the active group.
	xkb bool
	// codes finds a keysym's keycode and column (plain or with Shift) in the
	// active group; refresh rebuilds it before each key.
	codes map[uint32]keyPlace
	// spare are keycodes with no keysym, borrowed in turn for unmapped
	// characters; borrowed says what each one types now.
	spare    []uint8
	borrowed map[uint8]uint32
	next     int
	// wheel is the device whose scroll axes Scroll moves, or nil to click
	// the wheel buttons; wheelRest keeps the fraction of a valuator unit
	// not yet sent on each axis.
	wheel     *wheelDevice
	wheelRest [2]float64
	// scrollX and scrollY keep the fraction of a notch not yet scrolled.
	scrollX, scrollY int
	mu               sync.Mutex
}

type keyPlace struct {
	code  uint8
	shift bool
}

// wheelDevice is a pointer with smooth-scroll axes (a touchpad, a mouse with
// a high-resolution wheel): its vertical and horizontal valuators, -1 if it
// lacks one, with the valuator units of a notch.
type wheelDevice struct {
	id                   uint8
	vertical, horizontal int
	vInc, hInc           float64
}

// NewX11 prepares an injector on x: XTEST, the keyboard mapping and, when the
// desktop has one, a device to scroll with.
func NewX11(x *x11.Conn) (*X11, error) {
	if err := x.XTestInit(); err != nil {
		return nil, err
	}
	inj := &X11{x: x, xkb: x.XkbInit() == nil, borrowed: map[uint8]uint32{}}
	if err := inj.refresh(); err != nil {
		return nil, err
	}
	inj.wheel = findWheel(x)
	return inj, nil
}

// findWheel picks a pointer with smooth-scroll axes. Clients that scroll
// smoothly (kitty) ignore the wheel buttons whenever the desktop has such a
// device, and the XTEST pointer has none; scrolling through the device's own
// axes reaches them, and the server still clicks the wheel buttons for the
// rest.
func findWheel(x *x11.Conn) *wheelDevice {
	if x.XInputInit() != nil {
		return nil
	}
	devices, err := x.InputDevices()
	if err != nil {
		return nil
	}
	for _, d := range devices {
		if d.Use != x11.XISlavePointer || !d.Enabled || d.ID > 127 {
			continue
		}
		w := &wheelDevice{id: uint8(d.ID), vertical: -1, horizontal: -1}
		for _, a := range d.Scroll {
			if a.Increment == 0 {
				continue
			}
			switch a.Type {
			case x11.XIScrollVertical:
				w.vertical, w.vInc = a.Number, a.Increment
			case x11.XIScrollHorizontal:
				w.horizontal, w.hInc = a.Number, a.Increment
			}
		}
		// A motion carries at most 6 valuators, from the first.
		if w.vertical >= 0 && w.vertical < 6 && w.horizontal < 6 {
			return w
		}
	}
	return nil
}

// namedKeysym says whether s is a key rather than a character: the function
// keys and modifiers (0xfd00-0xffff) and the vendor ones (XF86 media keys).
func namedKeysym(s uint32) bool { return (s >= 0xfd00 && s <= 0xffff) || s >= 0x10000000 }

// refresh rereads the keyboard before keys are typed: the user may have
// switched layouts (an XKB group) or loaded another keymap since the last
// ones. Only the active group's columns count: a key types its keysym in
// that group, whatever the first group has there. Borrowed keycodes that a
// new keymap overwrote are forgotten.
func (k *X11) refresh() error {
	group := 0
	if k.xkb {
		g, err := k.x.XkbGroup()
		if err != nil {
			return err
		}
		group = g
	}
	m, err := k.x.GetKeyboardMapping()
	if err != nil {
		return err
	}
	k.mapping = m
	for code, sym := range k.borrowed {
		if s := m.Keysyms(code); len(s) == 0 || s[0] != sym {
			delete(k.borrowed, code)
		}
	}
	k.codes = map[uint32]keyPlace{}
	k.spare = k.spare[:0]
	add := func(code uint8, col int, s uint32) {
		if p, ok := k.codes[s]; !ok || (p.shift && col%2 == 0) {
			k.codes[s] = keyPlace{code: code, shift: col%2 == 1}
		}
	}
	for c := int(k.x.Setup.MinKeycode); c <= int(k.x.Setup.MaxKeycode); c++ {
		code := uint8(c)
		syms := m.Keysyms(code)
		if _, ok := k.borrowed[code]; ok {
			k.spare = append(k.spare, code)
			continue
		}
		empty := true
		for col, s := range syms {
			if s == 0 {
				continue
			}
			empty = false
			// The core mapping has the first group in columns 0-1 and the
			// second in 2-3; groups 3 and 4 have no fixed columns, so
			// their characters are borrowed.
			if group < 2 && col/2 == group && col < 4 {
				add(code, col, s)
			}
		}
		if empty && len(syms) > 0 {
			k.spare = append(k.spare, code)
		}
	}
	// Keys outside the layout's characters (Return, the modifiers, F1) are
	// in every group: take them from the first one when the active group
	// does not list them.
	for c := int(k.x.Setup.MinKeycode); c <= int(k.x.Setup.MaxKeycode); c++ {
		code := uint8(c)
		if _, ok := k.borrowed[code]; ok {
			continue
		}
		for col, s := range m.Keysyms(code) {
			if col > 1 || !namedKeysym(s) {
				continue
			}
			if _, ok := k.codes[s]; !ok {
				add(code, col, s)
			}
		}
	}
	return nil
}

// Conn is the X connection the injector uses.
func (k *X11) Conn() *x11.Conn { return k.x }

func (k *X11) screen() (int, int) {
	if g, err := k.x.GetGeometry(k.x.Setup.Root); err == nil {
		return int(g.Width), int(g.Height)
	}
	return int(k.x.Setup.Width), int(k.x.Setup.Height)
}

// Move moves the pointer by (dx, dy) as an absolute position, so the X
// server's pointer acceleration does not add to the client's curve.
func (k *X11) Move(dx, dy int) error {
	p, err := k.x.QueryPointer()
	if err != nil {
		return err
	}
	return k.MoveTo(int(p.X)+dx, int(p.Y)+dy)
}

// MoveTo puts the pointer at (x, y) of the root window, kept on the screen.
func (k *X11) MoveTo(x, y int) error {
	w, h := k.screen()
	x = max(0, min(x, w-1))
	y = max(0, min(y, h-1))
	return k.x.FakeInput(x11.MotionNotify, 0, int16(x), int16(y))
}

func (k *X11) button(b uint8, down bool) error {
	kind := uint8(x11.ButtonRelease)
	if down {
		kind = x11.ButtonPress
	}
	return k.x.FakeInput(kind, b, 0, 0)
}

// Button presses or releases a protocol button (X11: 1 left, 2 middle, 3 right).
func (k *X11) Button(button uint8, down bool) error {
	switch button {
	case protocol.ButtonLeft:
		return k.button(1, down)
	case protocol.ButtonMiddle:
		return k.button(2, down)
	case protocol.ButtonRight:
		return k.button(3, down)
	}
	return nil // a button from a newer client
}

// Scroll scrolls through the wheel device's axes when there is one, and
// otherwise clicks the wheel buttons (4 up, 5 down, 6 left, 7 right) once
// per whole notch, keeping the remainder for the next call.
func (k *X11) Scroll(dx, dy int) error {
	if k.wheel != nil {
		err := k.smoothScroll(dx, dy)
		if err == nil {
			return nil
		}
		// The device went away (unplugged, disabled): use the buttons.
		k.wheel = nil
	}
	k.scrollY += dy
	k.scrollX += dx
	clicks := func(acc *int, pos, neg uint8) error {
		for *acc >= 120 || *acc <= -120 {
			b := pos
			if *acc < 0 {
				b = neg
				*acc += 120
			} else {
				*acc -= 120
			}
			if err := k.button(b, true); err != nil {
				return err
			}
			if err := k.button(b, false); err != nil {
				return err
			}
		}
		return nil
	}
	if err := clicks(&k.scrollY, 4, 5); err != nil {
		return err
	}
	return clicks(&k.scrollX, 7, 6)
}

// smoothScroll moves the wheel device's scroll axes by (dx, dy) in 1/120 of
// a notch. The valuators grow downwards and rightwards, the protocol's dy
// upwards. The motion starts at valuator 0 (x and y, unmoved): the X server
// reads an XTEST motion's values as if they started there, whatever its
// first valuator says.
func (k *X11) smoothScroll(dx, dy int) error {
	w := k.wheel
	deltas := make([]int32, max(w.vertical, w.horizontal)+1)
	step := func(axis int, units float64, rest *float64) {
		v := *rest + units
		n := int32(v)
		*rest = v - float64(n)
		deltas[axis] = n
	}
	step(w.vertical, -float64(dy)*w.vInc/120, &k.wheelRest[0])
	if w.horizontal >= 0 {
		step(w.horizontal, float64(dx)*w.hInc/120, &k.wheelRest[1])
	}
	if err := k.x.FakeDeviceMotion(w.id, 0, deltas); err != nil {
		return err
	}
	// The motion has no reply: wait for its error, if any, to fall back.
	return k.x.Sync()
}

func (k *X11) keycode(kind uint8, code uint8) error { return k.x.FakeInput(kind, code, 0, 0) }

// place returns where keysym is, borrowing a spare keycode if the layout
// lacks it.
func (k *X11) place(keysym uint32) (keyPlace, error) {
	if p, ok := k.codes[keysym]; ok {
		return p, nil
	}
	for code, s := range k.borrowed {
		if s == keysym {
			return keyPlace{code: code}, nil
		}
	}
	if len(k.spare) == 0 {
		return keyPlace{}, fmt.Errorf("x11: no spare keycode to type keysym %#x", keysym)
	}
	code := k.spare[k.next%len(k.spare)]
	k.next++
	if err := k.x.ChangeKeyboardMapping(code, k.mapping.PerCode, []uint32{keysym, keysym}); err != nil {
		return keyPlace{}, err
	}
	// Wait for the server, so the client that gets the key has had the
	// MappingNotify before it.
	if err := k.x.Sync(); err != nil {
		return keyPlace{}, err
	}
	k.borrowed[code] = keysym
	return keyPlace{code: code}, nil
}

// tap presses and releases keysym, with Shift around it when it sits in the
// shifted column.
func (k *X11) tap(keysym uint32) error {
	p, err := k.place(keysym)
	if err != nil {
		return err
	}
	var shift keyPlace
	if p.shift {
		if shift, err = k.place(keysymShiftL); err != nil {
			return err
		}
		if err := k.keycode(x11.KeyPress, shift.code); err != nil {
			return err
		}
	}
	err = errors.Join(k.keycode(x11.KeyPress, p.code), k.keycode(x11.KeyRelease, p.code))
	if p.shift {
		err = errors.Join(err, k.keycode(x11.KeyRelease, shift.code))
	}
	return err
}

// Text types s character by character, whatever the layout: line breaks and
// tabs as the Return and Tab keys.
func (k *X11) Text(s string) error {
	if err := k.refresh(); err != nil {
		return err
	}
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
		if err := k.tap(sym); err != nil {
			return err
		}
	}
	return k.x.Sync()
}

// Key presses or releases a protocol key; with ActionPress the modifiers in
// mods are held around it.
func (k *X11) Key(key uint16, mods uint8, action uint8) error {
	sym, ok := keyKeysym(key)
	if !ok {
		return nil // a key from a newer client
	}
	if err := k.refresh(); err != nil {
		return err
	}
	p, err := k.place(sym)
	if err != nil {
		return err
	}
	switch action {
	case protocol.ActionDown:
		return k.keycode(x11.KeyPress, p.code)
	case protocol.ActionUp:
		return k.keycode(x11.KeyRelease, p.code)
	}
	var held []uint8
	for _, m := range []struct {
		bit uint8
		sym uint32
	}{{protocol.ModCtrl, keysymControlL}, {protocol.ModAlt, keysymAltL}, {protocol.ModShift, keysymShiftL}, {protocol.ModMeta, keysymSuperL}} {
		if mods&m.bit == 0 {
			continue
		}
		mp, err := k.place(m.sym)
		if err != nil {
			return err
		}
		if err := k.keycode(x11.KeyPress, mp.code); err != nil {
			return err
		}
		held = append(held, mp.code)
	}
	err = errors.Join(k.keycode(x11.KeyPress, p.code), k.keycode(x11.KeyRelease, p.code))
	for i := len(held) - 1; i >= 0; i-- {
		err = errors.Join(err, k.keycode(x11.KeyRelease, held[i]))
	}
	return err
}

// Blocked is always false: a locked X screen still takes input (the locker's
// own window gets it).
func (k *X11) Blocked() bool { return false }

// Restore gives the borrowed keycodes back their empty mapping, after a
// moment: a client reads a key's keysym only when it handles the key, and
// the last keys typed may still be waiting in its queue.
func (k *X11) Restore() {
	if len(k.borrowed) > 0 {
		time.Sleep(restoreDelay)
	}
	for code := range k.borrowed {
		_ = k.x.ChangeKeyboardMapping(code, k.mapping.PerCode, nil)
	}
	k.borrowed = map[uint8]uint32{}
	_ = k.x.Sync()
}

// restoreDelay is how long Restore lets the clients read the last keys.
const restoreDelay = 300 * time.Millisecond

// Keysyms used by name.
const (
	keysymReturn   = 0xff0d
	keysymTab      = 0xff09
	keysymShiftL   = 0xffe1
	keysymControlL = 0xffe3
	keysymAltL     = 0xffe9
	keysymSuperL   = 0xffeb
)

// runeKeysym is the keysym of a character: Latin-1 keysyms equal the code
// point, every other character is 0x01000000 + its code point.
func runeKeysym(r rune) uint32 {
	if (r >= 0x20 && r <= 0x7e) || (r >= 0xa0 && r <= 0xff) {
		return uint32(r)
	}
	return 0x01000000 | uint32(r)
}

// keyKeysym translates a protocol key into its keysym.
func keyKeysym(key uint16) (uint32, bool) {
	switch {
	case key >= protocol.KeyF1 && key <= protocol.KeyF12:
		return 0xffbe + uint32(key-protocol.KeyF1), true
	case key >= protocol.KeyA && key <= protocol.KeyZ:
		return 'a' + uint32(key-protocol.KeyA), true
	case key >= protocol.Key0 && key <= protocol.Key9:
		return '0' + uint32(key-protocol.Key0), true
	}
	sym, ok := map[uint16]uint32{
		protocol.KeyEnter: keysymReturn, protocol.KeyEscape: 0xff1b, protocol.KeyBackspace: 0xff08,
		protocol.KeyTab: keysymTab, protocol.KeySpace: 0x20, protocol.KeyDelete: 0xffff,
		protocol.KeyInsert: 0xff63, protocol.KeyHome: 0xff50, protocol.KeyEnd: 0xff57,
		protocol.KeyPageUp: 0xff55, protocol.KeyPageDown: 0xff56, protocol.KeyArrowLeft: 0xff51,
		protocol.KeyArrowRight: 0xff53, protocol.KeyArrowUp: 0xff52, protocol.KeyArrowDown: 0xff54,
		protocol.KeyPrintScreen: 0xff61, protocol.KeyContextMenu: 0xff67,
		protocol.KeyVolumeUp: 0x1008ff13, protocol.KeyVolumeDown: 0x1008ff11, protocol.KeyVolumeMute: 0x1008ff12,
		protocol.KeyMediaPlayPause: 0x1008ff14, protocol.KeyMediaNext: 0x1008ff17, protocol.KeyMediaPrevious: 0x1008ff16,
		protocol.KeyShift: keysymShiftL, protocol.KeyCtrl: keysymControlL, protocol.KeyAlt: keysymAltL,
		protocol.KeyMeta: keysymSuperL,
	}[key]
	return sym, ok
}
