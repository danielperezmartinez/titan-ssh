// Package screen serves a remote desktop session (ADR-0029): it captures the
// screen, sends the tiles that changed since the last frame (FILL, ZLIB or
// JPEG) at the pace the client acknowledges them, and passes the client's
// input frames to an injector.
package screen

import (
	"bytes"
	"compress/zlib"
	"image"
	"image/jpeg"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/protocol"
)

// TileSize is the side of a tile in pixels; edge tiles are smaller.
const TileSize = 64

// maxRun is how many neighbouring tiles of one row and encoding go out as a
// single rectangle, which saves the per-image headers (a JPEG's tables).
const maxRun = 8

// paletteMax is the most colours a tile can have and still go lossless when
// lossy encoding is allowed: interface and text stay crisp, photos and
// gradients go as JPEG.
const paletteMax = 256

// zlibLevel trades speed for size on lossless tiles.
const zlibLevel = 6

// Encoder turns captured frames (pixels 0x00RRGGBB, row after row) into the
// SCREEN_TILE frames that bring the client's copy up to date. It remembers
// what it sent, so the next frame carries only what changed.
type Encoder struct {
	w, h   int
	tw, th int      // tiles across and down
	prev   []uint32 // the pixels as last sent
	sent   bool     // prev holds a whole frame
	lossy  []bool   // per tile: last sent as JPEG, to refine when still
	zbuf   bytes.Buffer
	zw     *zlib.Writer
	rgb    []byte
	img    *image.RGBA
	// hint, when set, marks the tiles the next Encode may find changed.
	hint []bool
}

// NewEncoder returns an encoder for a w×h screen.
func NewEncoder(w, h int) *Encoder {
	tw, th := (w+TileSize-1)/TileSize, (h+TileSize-1)/TileSize
	e := &Encoder{w: w, h: h, tw: tw, th: th, prev: make([]uint32, w*h), lossy: make([]bool, tw*th)}
	e.zw, _ = zlib.NewWriterLevel(&e.zbuf, zlibLevel)
	return e
}

// tile kinds, as the encoder classifies them.
const (
	kindSame = iota // unchanged: nothing to send
	kindFill
	kindZlib
	kindJPEG
)

type tileInfo struct {
	kind  int
	color uint32 // kindFill
}

// Encode sends (through emit) the tiles of cur that differ from the last
// frame, or all of them if full or nothing was sent yet. quality is the JPEG
// quality, 0 for lossless only. It returns how many tiles changed.
func (e *Encoder) Encode(cur []uint32, full bool, quality int, emit func(protocol.Frame) error) (int, error) {
	full = full || !e.sent
	infos := make([]tileInfo, e.tw*e.th)
	changed := 0
	for ty := 0; ty < e.th; ty++ {
		for tx := 0; tx < e.tw; tx++ {
			x, y, w, h := e.rect(tx, ty)
			if !full && e.hint != nil && !e.hint[ty*e.tw+tx] {
				continue
			}
			if !full && !e.differs(cur, x, y, w, h) {
				continue
			}
			infos[ty*e.tw+tx] = classify(cur, e.w, x, y, w, h, quality)
			changed++
		}
	}
	if err := e.emitRuns(cur, infos, quality, emit); err != nil {
		return changed, err
	}
	e.sent = true
	e.hint = nil
	return changed, nil
}

// Hint says that, in the next Encode, only the tiles touching rects (from a
// Damager) may have changed: the others are not compared. A full frame
// ignores it, and it only lasts for one Encode.
func (e *Encoder) Hint(rects []image.Rectangle) {
	if e.hint == nil {
		e.hint = make([]bool, e.tw*e.th)
	} else {
		clear(e.hint)
	}
	bounds := image.Rect(0, 0, e.w, e.h)
	for _, r := range rects {
		r = r.Intersect(bounds)
		if r.Empty() {
			continue
		}
		for ty := r.Min.Y / TileSize; ty <= (r.Max.Y-1)/TileSize; ty++ {
			for tx := r.Min.X / TileSize; tx <= (r.Max.X-1)/TileSize; tx++ {
				e.hint[ty*e.tw+tx] = true
			}
		}
	}
}

// Refine sends again, lossless, the tiles last sent as JPEG, so a screen that
// stopped changing ends up exact. It returns how many it sent.
func (e *Encoder) Refine(emit func(protocol.Frame) error) (int, error) {
	infos := make([]tileInfo, e.tw*e.th)
	n := 0
	for i, l := range e.lossy {
		if l {
			infos[i] = tileInfo{kind: kindZlib}
			n++
		}
	}
	if n == 0 {
		return 0, nil
	}
	return n, e.emitRuns(e.prev, infos, 0, emit)
}

// Lossy reports whether some tile on the client is still a JPEG.
func (e *Encoder) Lossy() bool {
	for _, l := range e.lossy {
		if l {
			return true
		}
	}
	return false
}

func (e *Encoder) rect(tx, ty int) (x, y, w, h int) {
	x, y = tx*TileSize, ty*TileSize
	return x, y, min(TileSize, e.w-x), min(TileSize, e.h-y)
}

func (e *Encoder) differs(cur []uint32, x, y, w, h int) bool {
	for r := y; r < y+h; r++ {
		a, b := cur[r*e.w+x:r*e.w+x+w], e.prev[r*e.w+x:r*e.w+x+w]
		for i := range a {
			if a[i] != b[i] {
				return true
			}
		}
	}
	return false
}

// classify picks a changed tile's encoding.
func classify(px []uint32, stride, x, y, w, h, quality int) tileInfo {
	first := px[y*stride+x]
	var seen map[uint32]struct{}
	colors := 1
	for r := y; r < y+h; r++ {
		for _, p := range px[r*stride+x : r*stride+x+w] {
			if p == first {
				continue
			}
			if seen == nil {
				if quality == 0 {
					return tileInfo{kind: kindZlib}
				}
				seen = make(map[uint32]struct{}, 64)
				seen[first] = struct{}{}
			}
			if _, ok := seen[p]; !ok {
				seen[p] = struct{}{}
				colors++
				if colors > paletteMax {
					return tileInfo{kind: kindJPEG}
				}
			}
		}
	}
	if colors == 1 {
		return tileInfo{kind: kindFill, color: first}
	}
	return tileInfo{kind: kindZlib}
}

// emitRuns sends the classified tiles, joining neighbours of one row that
// share an encoding (and, for FILL, a colour), and records them as sent.
func (e *Encoder) emitRuns(src []uint32, infos []tileInfo, quality int, emit func(protocol.Frame) error) error {
	for ty := 0; ty < e.th; ty++ {
		for tx := 0; tx < e.tw; {
			in := infos[ty*e.tw+tx]
			if in.kind == kindSame {
				tx++
				continue
			}
			run := 1
			for tx+run < e.tw && run < maxRun {
				next := infos[ty*e.tw+tx+run]
				if next.kind != in.kind || (in.kind == kindFill && next.color != in.color) {
					break
				}
				run++
			}
			x, y, _, h := e.rect(tx, ty)
			lx, _, lw, _ := e.rect(tx+run-1, ty)
			w := lx + lw - x
			f, err := e.encodeRect(src, x, y, w, h, in, quality)
			if err != nil {
				return err
			}
			if err := emit(f); err != nil {
				return err
			}
			for i := 0; i < run; i++ {
				e.lossy[ty*e.tw+tx+i] = in.kind == kindJPEG
			}
			if &src[0] != &e.prev[0] {
				for r := y; r < y+h; r++ {
					copy(e.prev[r*e.w+x:r*e.w+x+w], src[r*e.w+x:r*e.w+x+w])
				}
			}
			tx += run
		}
	}
	return nil
}

func (e *Encoder) encodeRect(src []uint32, x, y, w, h int, in tileInfo, quality int) (protocol.Frame, error) {
	f := protocol.Frame{Type: protocol.TypeScreenTile, X: x, Y: y, Width: w, Height: h}
	switch in.kind {
	case kindFill:
		f.Encoding = protocol.EncodingFill
		f.Bytes = []byte{byte(in.color >> 16), byte(in.color >> 8), byte(in.color)}
	case kindJPEG:
		f.Encoding = protocol.EncodingJPEG
		if e.img == nil || e.img.Rect.Dx() < w || e.img.Rect.Dy() < h {
			e.img = image.NewRGBA(image.Rect(0, 0, TileSize*maxRun, TileSize))
		}
		img := e.img.SubImage(image.Rect(0, 0, w, h)).(*image.RGBA)
		for r := 0; r < h; r++ {
			row := img.Pix[r*img.Stride:]
			for c, p := range src[(y+r)*e.w+x : (y+r)*e.w+x+w] {
				row[c*4], row[c*4+1], row[c*4+2], row[c*4+3] = byte(p>>16), byte(p>>8), byte(p), 0xff
			}
		}
		var b bytes.Buffer
		if err := jpeg.Encode(&b, img, &jpeg.Options{Quality: quality}); err != nil {
			return f, err
		}
		f.Bytes = b.Bytes()
	default:
		f.Encoding = protocol.EncodingZlib
		n := w * h * 3
		if cap(e.rgb) < n {
			e.rgb = make([]byte, n)
		}
		rgb := e.rgb[:n]
		i := 0
		for r := 0; r < h; r++ {
			for _, p := range src[(y+r)*e.w+x : (y+r)*e.w+x+w] {
				rgb[i], rgb[i+1], rgb[i+2] = byte(p>>16), byte(p>>8), byte(p)
				i += 3
			}
		}
		e.zbuf.Reset()
		e.zw.Reset(&e.zbuf)
		if _, err := e.zw.Write(rgb); err != nil {
			return f, err
		}
		if err := e.zw.Close(); err != nil {
			return f, err
		}
		f.Bytes = append([]byte(nil), e.zbuf.Bytes()...)
	}
	return f, nil
}
