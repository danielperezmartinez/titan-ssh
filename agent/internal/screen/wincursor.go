package screen

// Windows cursor images, turned into straight-alpha ARGB. They live outside
// gdi_windows.go so they are tested on every system.

// colourCursor builds a colour cursor from its colour and AND-mask bitmaps
// (both read as 32-bit pixels). A 32-bit cursor with an alpha channel uses
// it, straight; an older one without it is opaque where the mask is clear.
func colourCursor(colour, mask []uint32) []uint32 {
	out := make([]uint32, len(colour))
	alpha := false
	for _, p := range colour {
		if p>>24 != 0 {
			alpha = true
			break
		}
	}
	for i, p := range colour {
		switch {
		case alpha:
			out[i] = p
		case mask[i]&0xffffff == 0:
			out[i] = 0xff000000 | p&0xffffff
		}
	}
	return out
}

// monochromeCursor builds a w×h monochrome cursor from its AND and XOR masks:
// AND clear is black or white, AND set is transparent or, with XOR set, the
// inverted screen. Inverted pixels (the text I-beam is all of them) cannot be
// drawn on the client, so they are black with a white outline, visible on any
// background.
func monochromeCursor(and, xor []uint32, w, h int) []uint32 {
	const (
		black = 0xff000000
		white = 0xffffffff
	)
	out := make([]uint32, w*h)
	inverted := make([]bool, w*h)
	for i := range out {
		a, x := and[i]&0xffffff != 0, xor[i]&0xffffff != 0
		switch {
		case !a && !x:
			out[i] = black
		case !a && x:
			out[i] = white
		case a && x:
			out[i] = black
			inverted[i] = true
		}
	}
	for y := range h {
		for x := range w {
			if !inverted[y*w+x] {
				continue
			}
			for _, d := range [4][2]int{{-1, 0}, {1, 0}, {0, -1}, {0, 1}} {
				nx, ny := x+d[0], y+d[1]
				if nx >= 0 && nx < w && ny >= 0 && ny < h && out[ny*w+nx] == 0 {
					out[ny*w+nx] = white
				}
			}
		}
	}
	return out
}
