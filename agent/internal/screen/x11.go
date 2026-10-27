package screen

import (
	"github.com/danielperezmartinez/titan-ssh/agent/internal/x11"
)

// X11 captures the root window of an X server: every monitor, as one image.
type X11 struct {
	x      *x11.Conn
	ptr    *x11.Conn // where the cursor is read
	cursor bool      // XFIXES is there
}

// NewX11 returns a capturer that reads the screen on x and the cursor on ptr.
// They should be two connections: a connection serves one request at a time,
// and the cursor must not wait for a whole screen's GetImage. Without XFIXES
// the cursor is not sent.
func NewX11(x, ptr *x11.Conn) *X11 {
	return &X11{x: x, ptr: ptr, cursor: ptr.XFixesInit() == nil}
}

// Size is the root window's current size, which follows RandR changes.
func (c *X11) Size() (int, int, error) {
	g, err := c.x.GetGeometry(c.x.Setup.Root)
	if err != nil {
		return 0, 0, err
	}
	return int(g.Width), int(g.Height), nil
}

// Capture reads the whole root window.
func (c *X11) Capture(dst []uint32, w, h int) error {
	return c.x.GetImage(0, 0, w, h, dst)
}

// Cursor reads the cursor through XFIXES (or just the pointer without it).
// XFIXES gives premultiplied ARGB; the protocol wants straight alpha.
func (c *X11) Cursor() (Cursor, error) {
	if !c.cursor {
		p, err := c.ptr.QueryPointer()
		return Cursor{X: int(p.X), Y: int(p.Y), Visible: false}, err
	}
	ci, err := c.ptr.GetCursorImage()
	if err != nil {
		return Cursor{}, err
	}
	px := ci.Pixels
	for i, p := range px {
		px[i] = unpremultiply(p)
	}
	return Cursor{
		X: int(ci.X), Y: int(ci.Y), Visible: true, Serial: ci.Serial,
		Width: int(ci.Width), Height: int(ci.Height), HotX: int(ci.HotX), HotY: int(ci.HotY), Pixels: px,
	}, nil
}

// unpremultiply turns a premultiplied ARGB pixel into straight alpha.
func unpremultiply(p uint32) uint32 {
	a := p >> 24
	if a == 0 || a == 255 {
		if a == 0 {
			return 0
		}
		return p
	}
	ch := func(v uint32) uint32 { return min(255, (v*255+a/2)/a) }
	return a<<24 | ch(p>>16&0xff)<<16 | ch(p>>8&0xff)<<8 | ch(p&0xff)
}
