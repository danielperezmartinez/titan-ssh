//go:build linux

package inject

import (
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/protocol"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/wayland"
	"golang.org/x/sys/unix"
)

// ErrNoVirtualInput reports a compositor without the virtual pointer and
// keyboard protocols.
var ErrNoVirtualInput = errors.New("the compositor offers no zwlr_virtual_pointer_v1 and zwp_virtual_keyboard_v1")

// Linux input event codes (linux/input-event-codes.h).
const (
	btnLeft   = 0x110
	btnRight  = 0x111
	btnMiddle = 0x112
)

// Wayland injects input into a wlroots-family compositor (ADR-0031 §2)
// through a virtual pointer (zwlr_virtual_pointer_v1) and a virtual keyboard
// (zwp_virtual_keyboard_v1). The keyboard carries the agent's own XKB
// keymap, one keysym per key, so it types any character whatever the
// user's layout: characters it lacks take a spare key and the keymap is
// sent again.
type Wayland struct {
	c       *wayland.Conn
	seat    uint32
	pointer uint32
	kbd     uint32
	start   time.Time
	// target takes POINTER_TO's point of the captured image to the
	// layout; nil without a capture (the mouse pad).
	target func(x, y int) (px, py, xExt, yExt uint32, ok bool)

	keymap *xkbKeymap
	// scrollX and scrollY keep the fraction of a notch not yet scrolled.
	scrollX, scrollY int
	// caps are the seat's capabilities (1 is the pointer).
	caps uint32
	// mods are the modifiers held down now, as XKB masks.
	mods uint32
}

// NewWayland creates the virtual pointer and keyboard on the first seat of c.
func NewWayland(c *wayland.Conn) (*Wayland, error) {
	pm, okP := c.Find("zwlr_virtual_pointer_manager_v1")
	km, okK := c.Find("zwp_virtual_keyboard_manager_v1")
	seat, okS := c.Find("wl_seat")
	if !okP || !okK || !okS {
		return nil, ErrNoVirtualInput
	}
	w := &Wayland{c: c, start: time.Now(), keymap: newXKBKeymap()}
	var err error
	if w.seat, _, err = c.Bind(seat, 5, w.seatEvent); err != nil {
		return nil, err
	}
	pmID, _, err := c.Bind(pm, 1, nil)
	if err != nil {
		return nil, err
	}
	kmID, _, err := c.Bind(km, 1, nil)
	if err != nil {
		return nil, err
	}
	if w.pointer, err = c.Create(nil, pmID, 0, wayland.Object(w.seat), wayland.New{}); err != nil { // create_virtual_pointer
		return nil, err
	}
	if w.kbd, err = c.Create(nil, kmID, 0, wayland.Object(w.seat), wayland.New{}); err != nil { // create_virtual_keyboard
		return nil, err
	}
	if err := w.sendKeymap(); err != nil {
		return nil, err
	}
	if err := c.Roundtrip(); err != nil {
		return nil, err
	}
	return w, nil
}

func (w *Wayland) seatEvent(op uint16, m *wayland.Message) {
	if op == 0 { // capabilities
		w.caps = m.Uint()
	}
}

// Pointer returns a wl_pointer of the seat, for the cursor capture
// sessions, once the seat has a pointer (the virtual one counts); 0 if not.
func (w *Wayland) Pointer() uint32 {
	if err := w.c.Roundtrip(); err != nil || w.caps&1 == 0 {
		return 0
	}
	id, err := w.c.Create(nil, w.seat, 0, wayland.New{}) // get_pointer
	if err != nil {
		return 0
	}
	return id
}

// SetTarget gives the injector the capture's mapping for MoveTo.
func (w *Wayland) SetTarget(f func(x, y int) (px, py, xExt, yExt uint32, ok bool)) { w.target = f }

func (w *Wayland) now() uint32 { return uint32(time.Since(w.start).Milliseconds()) }

func (w *Wayland) frame() error { return w.c.Request(w.pointer, 4) }

// Move moves the pointer by dx, dy.
func (w *Wayland) Move(dx, dy int) error {
	if err := w.c.Request(w.pointer, 0, w.now(), wayland.Fixed(dx), wayland.Fixed(dy)); err != nil { // motion
		return err
	}
	return w.frame()
}

// MoveTo puts the pointer at x, y of the captured image.
func (w *Wayland) MoveTo(x, y int) error {
	if w.target == nil {
		return nil
	}
	px, py, xe, ye, ok := w.target(x, y)
	if !ok {
		return nil // between monitors
	}
	if err := w.c.Request(w.pointer, 1, w.now(), px, py, xe, ye); err != nil { // motion_absolute
		return err
	}
	return w.frame()
}

// Button presses or releases a protocol button.
func (w *Wayland) Button(button uint8, down bool) error {
	var code uint32
	switch button {
	case protocol.ButtonLeft:
		code = btnLeft
	case protocol.ButtonRight:
		code = btnRight
	case protocol.ButtonMiddle:
		code = btnMiddle
	default:
		return nil // a button from a newer client
	}
	var state uint32
	if down {
		state = 1
	}
	if err := w.c.Request(w.pointer, 2, w.now(), code, state); err != nil { // button
		return err
	}
	return w.frame()
}

// Scroll turns the wheel once per whole notch, keeping the remainder.
// dy > 0 is up (Wayland's vertical axis grows downwards), dx > 0 right.
func (w *Wayland) Scroll(dx, dy int) error {
	w.scrollY += dy
	w.scrollX += dx
	notches := func(acc *int) int {
		n := *acc / 120
		*acc -= n * 120
		return n
	}
	ny, nx := notches(&w.scrollY), notches(&w.scrollX)
	if nx == 0 && ny == 0 {
		return nil
	}
	if err := w.c.Request(w.pointer, 5, uint32(0)); err != nil { // axis_source: wheel
		return err
	}
	t := w.now()
	for _, a := range []struct {
		axis uint32
		n    int
	}{{0, -ny}, {1, nx}} {
		if a.n == 0 {
			continue
		}
		if err := w.c.Request(w.pointer, 7, t, a.axis, wayland.Fixed(15*a.n), int32(a.n)); err != nil { // axis_discrete
			return err
		}
	}
	return w.frame()
}

// key presses or releases an evdev key code.
func (w *Wayland) key(code uint32, down bool) error {
	var state uint32
	if down {
		state = 1
	}
	if err := w.c.Request(w.kbd, 1, w.now(), code, state); err != nil { // key
		return err
	}
	// The compositor does not work the modifiers out of a virtual
	// keyboard's keys: they are sent as their own state.
	mask, ok := w.keymap.modMask(code)
	if !ok {
		return nil
	}
	if down {
		w.mods |= mask
	} else {
		w.mods &^= mask
	}
	return w.c.Request(w.kbd, 2, w.mods, uint32(0), uint32(0), uint32(0)) // modifiers
}

// codeFor is the key that types keysym, giving it a spare key (and sending
// the keymap again) if it has none.
func (w *Wayland) codeFor(keysym uint32) (uint32, error) {
	code, added := w.keymap.code(keysym)
	if added {
		if err := w.sendKeymap(); err != nil {
			return 0, err
		}
	}
	return code, nil
}

func (w *Wayland) tap(keysym uint32) error {
	code, err := w.codeFor(keysym)
	if err != nil {
		return err
	}
	return errors.Join(w.key(code, true), w.key(code, false))
}

// Text types s character by character: line breaks and tabs as the Return
// and Tab keys.
func (w *Wayland) Text(s string) error {
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
		if err := w.tap(sym); err != nil {
			return err
		}
	}
	return nil
}

// Key presses or releases a protocol key; with ActionPress the modifiers in
// mods are held around it.
func (w *Wayland) Key(key uint16, mods uint8, action uint8) error {
	sym, ok := keyKeysym(key)
	if !ok {
		return nil // a key from a newer client
	}
	code, err := w.codeFor(sym)
	if err != nil {
		return err
	}
	switch action {
	case protocol.ActionDown:
		return w.key(code, true)
	case protocol.ActionUp:
		return w.key(code, false)
	}
	var held []uint32
	for _, m := range []struct {
		bit uint8
		sym uint32
	}{{protocol.ModCtrl, keysymControlL}, {protocol.ModAlt, keysymAltL}, {protocol.ModShift, keysymShiftL}, {protocol.ModMeta, keysymSuperL}} {
		if mods&m.bit == 0 {
			continue
		}
		mc, err := w.codeFor(m.sym)
		if err != nil {
			return err
		}
		if err := w.key(mc, true); err != nil {
			return err
		}
		held = append(held, mc)
	}
	err = errors.Join(w.key(code, true), w.key(code, false))
	for i := len(held) - 1; i >= 0; i-- {
		err = errors.Join(err, w.key(held[i], false))
	}
	return err
}

// Blocked is always false: a locked wlroots session still takes input (the
// locker gets it).
func (w *Wayland) Blocked() bool { return false }

// sendKeymap gives the virtual keyboard the current keymap.
func (w *Wayland) sendKeymap() error {
	text := w.keymap.String()
	mem, err := wayland.NewMemfd("titan-agent-keymap", len(text)+1)
	if err != nil {
		return err
	}
	defer mem.Close()
	copy(mem.Data, text)
	_ = unix.Fsync(mem.Fd)
	return w.c.Request(w.kbd, 0, uint32(1), wayland.Fd(mem.Fd), uint32(len(text)+1)) // keymap: xkb_v1
}

// xkbKeymap is the virtual keyboard's layout: every key types one keysym,
// with no levels (letters have their capital with Shift), and the four
// modifiers act as such. Keys are numbered by
// their evdev code (XKB keycode minus 8).
type xkbKeymap struct {
	syms  []uint32 // by evdev code - 1
	codes map[uint32]uint32
	fixed int // keys before the spare ones
	next  int // the spare key to take next
}

// spareKeys are the keys left for characters outside the fixed set, taken
// in turn.
const spareKeys = 64

func newXKBKeymap() *xkbKeymap {
	k := &xkbKeymap{codes: map[uint32]uint32{}}
	add := func(sym uint32) {
		if _, ok := k.codes[sym]; !ok {
			k.syms = append(k.syms, sym)
			k.codes[sym] = uint32(len(k.syms))
		}
	}
	for _, s := range []uint32{keysymShiftL, keysymControlL, keysymAltL, keysymSuperL} {
		add(s)
	}
	for key := uint16(0); key < 0x400; key++ {
		if s, ok := keyKeysym(key); ok {
			add(s)
		}
	}
	for r := rune(0x20); r <= 0x7e; r++ {
		add(runeKeysym(r))
	}
	k.fixed = len(k.syms)
	for i := 0; i < spareKeys; i++ {
		k.syms = append(k.syms, 0)
	}
	return k
}

// code is the evdev code that types sym; added says a spare key was taken
// for it and the keymap must be sent again.
func (k *xkbKeymap) code(sym uint32) (uint32, bool) {
	if c, ok := k.codes[sym]; ok {
		return c, false
	}
	i := k.fixed + k.next%spareKeys
	k.next++
	if old := k.syms[i]; old != 0 {
		delete(k.codes, old)
	}
	k.syms[i] = sym
	k.codes[sym] = uint32(i + 1)
	return uint32(i + 1), true
}

// modMask is the XKB modifier mask of a modifier key: Shift, Control, Mod1
// (Alt) and Mod4 (Super), in the order of xkbcommon's real modifiers.
func (k *xkbKeymap) modMask(code uint32) (uint32, bool) {
	for _, m := range []struct {
		sym  uint32
		mask uint32
	}{{keysymShiftL, 1 << 0}, {keysymControlL, 1 << 2}, {keysymAltL, 1 << 3}, {keysymSuperL, 1 << 6}} {
		if k.codes[m.sym] == code {
			return m.mask, true
		}
	}
	return 0, false
}

// String is the keymap in XKB's text format.
func (k *xkbKeymap) String() string {
	var b strings.Builder
	n := len(k.syms)
	b.WriteString("xkb_keymap {\n")
	fmt.Fprintf(&b, "xkb_keycodes \"titan\" {\n minimum = 8;\n maximum = %d;\n", max(255, n+8))
	for i := 0; i < n; i++ {
		fmt.Fprintf(&b, " <K%d> = %d;\n", i+1, i+9)
	}
	// Xwayland crashes when an X client asks for the indicators of a
	// keymap that has none (xkbcomp, kitty at start).
	b.WriteString(" indicator 1 = \"Caps Lock\";\n indicator 2 = \"Num Lock\";\n indicator 3 = \"Scroll Lock\";\n")
	b.WriteString("};\n")
	b.WriteString(`xkb_types "titan" {
 virtual_modifiers NumLock;
 type "ONE_LEVEL" { modifiers = none; level_name[Level1] = "Any"; };
 type "TWO_LEVEL" { modifiers = Shift; map[Shift] = Level2; level_name[Level1] = "Base"; level_name[Level2] = "Shift"; };
 type "ALPHABETIC" { modifiers = Shift+Lock; map[Shift] = Level2; map[Lock] = Level2; level_name[Level1] = "Base"; level_name[Level2] = "Caps"; };
 type "KEYPAD" { modifiers = Shift+NumLock; map[None] = Level1; map[Shift] = Level2; map[NumLock] = Level2; map[Shift+NumLock] = Level1; level_name[Level1] = "Base"; level_name[Level2] = "Number"; };
};
xkb_compatibility "titan" {
 interpret Shift_L { action = SetMods(modifiers=Shift); };
 interpret Control_L { action = SetMods(modifiers=Control); };
 interpret Alt_L { action = SetMods(modifiers=Mod1); };
 interpret Super_L { action = SetMods(modifiers=Mod4); };
 indicator "Caps Lock" { modifiers = Lock; };
 indicator "Num Lock" { modifiers = NumLock; };
 indicator "Scroll Lock" { modifiers = none; };
};
xkb_symbols "titan" {
`)
	for i, s := range k.syms {
		if s == 0 {
			continue
		}
		if s >= 'a' && s <= 'z' {
			// Letters take Shift (and Caps Lock) as a layout does, for
			// shortcuts such as Shift+A sent as keys.
			fmt.Fprintf(&b, " key <K%d> { type = \"ALPHABETIC\", [ 0x%x, 0x%x ] };\n", i+1, s, s-'a'+'A')
			continue
		}
		fmt.Fprintf(&b, " key <K%d> { [ 0x%x ] };\n", i+1, s)
	}
	for _, m := range []struct {
		mod string
		sym uint32
	}{{"Shift", keysymShiftL}, {"Control", keysymControlL}, {"Mod1", keysymAltL}, {"Mod4", keysymSuperL}} {
		fmt.Fprintf(&b, " modifier_map %s { <K%d> };\n", m.mod, k.codes[m.sym])
	}
	b.WriteString("};\n};\n")
	return b.String()
}
