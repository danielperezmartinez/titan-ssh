package x11

import (
	"errors"
	"fmt"
)

// Core request opcodes.
const (
	opGetGeometry           = 14
	opQueryPointer          = 38
	opGetInputFocus         = 43
	opGetImage              = 73
	opQueryExtension        = 98
	opChangeKeyboardMapping = 100
	opGetKeyboardMapping    = 101
)

// QueryExtension asks whether the server has the extension name, and its
// opcode. Answers are cached.
func (x *Conn) QueryExtension(name string) (Extension, error) {
	x.mu.Lock()
	e, ok := x.exts[name]
	x.mu.Unlock()
	if ok {
		return e, nil
	}
	n := len(name)
	req := request(8+n+pad4(n), opQueryExtension, 0)
	le.PutUint16(req[4:], uint16(n))
	copy(req[8:], name)
	reply, err := x.roundTrip(req)
	if err != nil {
		return e, err
	}
	e = Extension{Present: reply[8] != 0, Opcode: reply[9], FirstEvent: reply[10], FirstError: reply[11]}
	x.mu.Lock()
	x.exts[name] = e
	x.mu.Unlock()
	return e, nil
}

// extension returns the opcode of a present extension.
func (x *Conn) extension(name string) (uint8, error) {
	e, err := x.QueryExtension(name)
	if err != nil {
		return 0, err
	}
	if !e.Present {
		return 0, fmt.Errorf("x11: the server has no %s extension", name)
	}
	return e.Opcode, nil
}

// Geometry is a drawable's size and depth.
type Geometry struct {
	Depth         uint8
	X, Y          int16
	Width, Height uint16
}

// GetGeometry returns d's geometry; for the root window, the screen's
// current size (which follows RandR changes).
func (x *Conn) GetGeometry(d uint32) (Geometry, error) {
	req := request(8, opGetGeometry, 0)
	le.PutUint32(req[4:], d)
	reply, err := x.roundTrip(req)
	if err != nil {
		return Geometry{}, err
	}
	return Geometry{
		Depth: reply[1],
		X:     int16(le.Uint16(reply[12:])), Y: int16(le.Uint16(reply[14:])),
		Width: le.Uint16(reply[16:]), Height: le.Uint16(reply[18:]),
	}, nil
}

// Pointer is where the pointer is, on the root window.
type Pointer struct {
	X, Y int16
	Mask uint16 // buttons and modifiers held
}

// QueryPointer returns the pointer's position relative to the root window.
func (x *Conn) QueryPointer() (Pointer, error) {
	req := request(8, opQueryPointer, 0)
	le.PutUint32(req[4:], x.Setup.Root)
	reply, err := x.roundTrip(req)
	if err != nil {
		return Pointer{}, err
	}
	return Pointer{X: int16(le.Uint16(reply[16:])), Y: int16(le.Uint16(reply[18:])), Mask: le.Uint16(reply[24:])}, nil
}

// ErrPixelFormat reports a root window whose pixels this client cannot read:
// only 24 and 32-bit TrueColor with 32 bits per pixel are supported.
var ErrPixelFormat = errors.New("x11: unsupported pixel format (only 24/32-bit TrueColor)")

// Supported checks that the root window's pixel format is one GetImage can
// turn into RGB.
func (x *Conn) checkPixelFormat() error {
	s := x.Setup
	if s.RootDepth != 24 && s.RootDepth != 32 {
		return ErrPixelFormat
	}
	for _, f := range s.Formats {
		if f.Depth == s.RootDepth && f.BitsPerPixel != 32 {
			return ErrPixelFormat
		}
	}
	if s.RedMask != 0xff0000 || s.GreenMask != 0xff00 || s.BlueMask != 0xff {
		return ErrPixelFormat
	}
	return nil
}

// GetImage reads the w×h rectangle at (x0, y0) of the root window into dst as
// 32-bit pixels 0x00RRGGBB, row after row (len(dst) >= w*h). Large areas are
// read in bands so no reply gets near the server's limits.
func (x *Conn) GetImage(x0, y0, w, h int, dst []uint32) error {
	if err := x.checkPixelFormat(); err != nil {
		return err
	}
	if w <= 0 || h <= 0 {
		return nil
	}
	if len(dst) < w*h {
		return errors.New("x11: GetImage destination too small")
	}
	// Bands of at most ~4 MB.
	band := (4 << 20) / (w * 4)
	if band < 1 {
		band = 1
	}
	for y := 0; y < h; y += band {
		bh := min(band, h-y)
		req := request(20, opGetImage, 2) // ZPixmap
		le.PutUint32(req[4:], x.Setup.Root)
		le.PutUint16(req[8:], uint16(x0))
		le.PutUint16(req[10:], uint16(y0+y))
		le.PutUint16(req[12:], uint16(w))
		le.PutUint16(req[14:], uint16(bh))
		le.PutUint32(req[16:], 0xffffffff)
		reply, err := x.roundTrip(req)
		if err != nil {
			return err
		}
		data := reply[32:]
		if len(data) < w*bh*4 {
			return errors.New("x11: short GetImage reply")
		}
		out := dst[y*w : (y+bh)*w]
		if x.Setup.ImageByteOrder == 0 { // LSBFirst
			for i := range out {
				out[i] = le.Uint32(data[i*4:]) & 0xffffff
			}
		} else {
			for i := range out {
				p := data[i*4:]
				out[i] = uint32(p[1])<<16 | uint32(p[2])<<8 | uint32(p[3])
			}
		}
	}
	return nil
}

// KeyboardMapping is the server's keycode → keysyms table.
type KeyboardMapping struct {
	First   uint8
	PerCode int
	Syms    []uint32 // (keycode-First)*PerCode + column
}

// Keysyms returns the keysyms of keycode.
func (m KeyboardMapping) Keysyms(keycode uint8) []uint32 {
	i := int(keycode-m.First) * m.PerCode
	if keycode < m.First || i+m.PerCode > len(m.Syms) {
		return nil
	}
	return m.Syms[i : i+m.PerCode]
}

// GetKeyboardMapping reads the mapping of every keycode.
func (x *Conn) GetKeyboardMapping() (KeyboardMapping, error) {
	first, last := x.Setup.MinKeycode, x.Setup.MaxKeycode
	req := request(8, opGetKeyboardMapping, 0)
	req[4] = first
	req[5] = last - first + 1
	reply, err := x.roundTrip(req)
	if err != nil {
		return KeyboardMapping{}, err
	}
	m := KeyboardMapping{First: first, PerCode: int(reply[1])}
	n := int(le.Uint32(reply[4:]))
	for i := 0; i < n && 32+i*4+4 <= len(reply); i++ {
		m.Syms = append(m.Syms, le.Uint32(reply[32+i*4:]))
	}
	return m, nil
}

// ChangeKeyboardMapping sets the keysyms of one keycode (the rest of its
// columns, up to perCode, become NoSymbol).
func (x *Conn) ChangeKeyboardMapping(keycode uint8, perCode int, keysyms []uint32) error {
	req := request(8+perCode*4, opChangeKeyboardMapping, 1)
	req[4] = keycode
	req[5] = uint8(perCode)
	for i := 0; i < perCode && i < len(keysyms); i++ {
		le.PutUint32(req[8+i*4:], keysyms[i])
	}
	return x.sendOnly(req)
}
