package screen

import (
	"errors"
	"image"
)

// Part is one source of a Composite: a monitor, placed at X, Y of the whole
// desktop (in captured pixels; they may be negative, as monitors left of or
// above the primary one are).
type Part struct {
	X, Y int
	Capturer
}

// Composite captures several monitors as one image, the whole desktop, as X11
// gives it (ADR-0031 §5). The image's top left corner is the smallest X and Y
// of the parts; what no part covers is black. Each part keeps its own size,
// which may change between captures. It is a Blocker when any part is.
type Composite struct {
	Parts []Part

	// From the last Size: the origin and each part's size.
	minX, minY int
	sizes      [][2]int
	scratch    []uint32
	// last is the part that showed the cursor last, and lastX, lastY where
	// it was, in the whole desktop's coordinates.
	last         int
	lastX, lastY int
}

// Size is the bounding box of the parts.
func (c *Composite) Size() (int, int, error) {
	if len(c.Parts) == 0 {
		return 0, 0, errNoParts
	}
	c.sizes = c.sizes[:0]
	minX, minY := c.Parts[0].X, c.Parts[0].Y
	maxX, maxY := minX, minY
	for _, p := range c.Parts {
		w, h, err := p.Size()
		if err != nil {
			return 0, 0, err
		}
		c.sizes = append(c.sizes, [2]int{w, h})
		minX, minY = min(minX, p.X), min(minY, p.Y)
		maxX, maxY = max(maxX, p.X+w), max(maxY, p.Y+h)
	}
	c.minX, c.minY = minX, minY
	return maxX - minX, maxY - minY, nil
}

// Capture reads every part into its place of dst (w×h, from the last Size).
func (c *Composite) Capture(dst []uint32, w, h int) error {
	clear(dst[:w*h])
	for i, p := range c.Parts {
		pw, ph := c.sizes[i][0], c.sizes[i][1]
		if pw*ph > len(c.scratch) {
			c.scratch = make([]uint32, pw*ph)
		}
		buf := c.scratch[:pw*ph]
		if err := p.Capture(buf, pw, ph); err != nil {
			return err
		}
		ox, oy := p.X-c.minX, p.Y-c.minY
		for y := 0; y < ph && oy+y < h; y++ {
			n := min(pw, w-ox)
			copy(dst[(oy+y)*w+ox:(oy+y)*w+ox+n], buf[y*pw:y*pw+n])
		}
	}
	return nil
}

// Cursor is the cursor of the part that shows it, in the whole desktop's
// coordinates. When no part does, it is hidden where the last one was.
func (c *Composite) Cursor() (Cursor, error) {
	var first error
	for i, p := range c.Parts {
		cur, err := p.Cursor()
		if err != nil {
			if first == nil {
				first = err
			}
			continue
		}
		if !cur.Visible {
			continue
		}
		c.last = i
		cur.X += p.X - c.minX
		cur.Y += p.Y - c.minY
		// A cursor moving to another monitor may need its image again.
		cur.Serial = cur.Serial<<4 | uint32(i&0xf)
		c.lastX, c.lastY = cur.X, cur.Y
		return cur, nil
	}
	if first != nil {
		return Cursor{}, first
	}
	// Hidden (KWin hides it while Konsole hides the pointer): keep where it
	// was, as the client centres its zoom on it.
	return Cursor{X: c.lastX, Y: c.lastY, Serial: uint32(c.last & 0xf)}, nil
}

// Damage joins the parts' damage, each moved to its place. It knows nothing
// (ok false) when any part is not a Damager or does not know this time.
func (c *Composite) Damage() ([]image.Rectangle, bool) {
	var out []image.Rectangle
	for _, p := range c.Parts {
		d, ok := p.Capturer.(Damager)
		if !ok {
			return nil, false
		}
		rects, ok := d.Damage()
		if !ok {
			return nil, false
		}
		off := image.Pt(p.X-c.minX, p.Y-c.minY)
		for _, r := range rects {
			out = append(out, r.Add(off))
		}
	}
	return out, true
}

// Blocked is true while any part is blocked.
func (c *Composite) Blocked() bool {
	for _, p := range c.Parts {
		if b, ok := p.Capturer.(Blocker); ok && b.Blocked() {
			return true
		}
	}
	return false
}

// Locate finds the part under x, y of the whole desktop (in the last Size's
// coordinates) and the point in that part's own pixels, for the input of
// POINTER_TO. ok is false between monitors.
func (c *Composite) Locate(x, y int) (part, px, py int, ok bool) {
	for i, p := range c.Parts {
		if i >= len(c.sizes) {
			break
		}
		px, py = x-(p.X-c.minX), y-(p.Y-c.minY)
		if px >= 0 && py >= 0 && px < c.sizes[i][0] && py < c.sizes[i][1] {
			return i, px, py, true
		}
	}
	return 0, 0, 0, false
}

// errNoParts reports a Composite without parts.
var errNoParts = errors.New("screen: a composite without monitors")
