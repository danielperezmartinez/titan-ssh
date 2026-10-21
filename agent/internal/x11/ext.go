package x11

import "errors"

// XTEST event types for FakeInput.
const (
	KeyPress      = 2
	KeyRelease    = 3
	ButtonPress   = 4
	ButtonRelease = 5
	MotionNotify  = 6
)

// XTestInit checks that the server has XTEST 2.2 or later.
func (x *Conn) XTestInit() error {
	op, err := x.extension("XTEST")
	if err != nil {
		return err
	}
	req := request(8, op, 0) // GetVersion
	req[4] = 2
	le.PutUint16(req[6:], 2)
	reply, err := x.roundTrip(req)
	if err != nil {
		return err
	}
	if reply[1] < 2 {
		return errors.New("x11: XTEST is older than 2.2")
	}
	return nil
}

// FakeInput injects one event through XTEST, as if it came from a device:
// key (detail = keycode) or button (detail = button) press and release, or
// pointer motion to (rootX, rootY) on the root window (detail 0: absolute).
func (x *Conn) FakeInput(kind, detail uint8, rootX, rootY int16) error {
	op, err := x.extension("XTEST")
	if err != nil {
		return err
	}
	req := request(36, op, 2) // FakeInput
	req[4] = kind
	req[5] = detail
	// time (8) = CurrentTime
	if kind == MotionNotify {
		le.PutUint32(req[12:], x.Setup.Root)
		le.PutUint16(req[24:], uint16(rootX))
		le.PutUint16(req[26:], uint16(rootY))
	}
	return x.sendOnly(req)
}

// Cursor is the current cursor image, from XFIXES.
type Cursor struct {
	X, Y          int16 // pointer position on the root window
	Width, Height uint16
	HotX, HotY    uint16
	Serial        uint32 // changes when the image changes
	Pixels        []uint32
}

// XFixesInit announces XFIXES 4.0, which the server requires before any
// other XFIXES request.
func (x *Conn) XFixesInit() error {
	op, err := x.extension("XFIXES")
	if err != nil {
		return err
	}
	req := request(12, op, 0) // QueryVersion
	le.PutUint32(req[4:], 4)
	le.PutUint32(req[8:], 0)
	_, err = x.roundTrip(req)
	return err
}

// GetCursorImage returns the cursor's position, serial and image (ARGB,
// premultiplied alpha). Call XFixesInit first.
func (x *Conn) GetCursorImage() (Cursor, error) {
	op, err := x.extension("XFIXES")
	if err != nil {
		return Cursor{}, err
	}
	reply, err := x.roundTrip(request(4, op, 4))
	if err != nil {
		return Cursor{}, err
	}
	c := Cursor{
		X: int16(le.Uint16(reply[8:])), Y: int16(le.Uint16(reply[10:])),
		Width: le.Uint16(reply[12:]), Height: le.Uint16(reply[14:]),
		HotX: le.Uint16(reply[16:]), HotY: le.Uint16(reply[18:]),
		Serial: le.Uint32(reply[20:]),
	}
	n := int(c.Width) * int(c.Height)
	if len(reply) < 32+n*4 {
		return c, errors.New("x11: short GetCursorImage reply")
	}
	c.Pixels = make([]uint32, n)
	for i := range c.Pixels {
		c.Pixels[i] = le.Uint32(reply[32+i*4:])
	}
	return c, nil
}
