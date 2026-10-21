package screen

import (
	"image"
	"net"
	"testing"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/protocol"
)

// damaging is a fakeCapturer that reports the damage a test gives it.
type damaging struct {
	*fakeCapturer
	rects []image.Rectangle
	known bool
	reads int
}

func (d *damaging) Damage() ([]image.Rectangle, bool) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.reads++
	return d.rects, d.known
}

func (d *damaging) setDamage(rects []image.Rectangle, known bool) {
	d.mu.Lock()
	d.rects, d.known = rects, known
	d.mu.Unlock()
}

func TestServeComparesOnlyDamage(t *testing.T) {
	w, h, px := testScreen()
	cp := &damaging{fakeCapturer: &fakeCapturer{w: w, h: h, px: px}, known: true}
	cli, srv := net.Pipe()
	defer cli.Close()
	go func() {
		_ = Serve(srv, cp, nil, Options{Interval: 5 * time.Millisecond, RefineAfter: time.Hour})
		srv.Close()
	}()
	r := &reader{t: t, c: cli}
	ack := func(seq uint32) {
		_, _ = cli.Write(protocol.Encode(protocol.Frame{Type: protocol.TypeScreenAck, Seq: seq}))
	}
	_, _ = cli.Write(protocol.Encode(protocol.Frame{Type: protocol.TypeScreenHello, Version: 1, Quality: 0}))
	// The first frame is whole although the damage is empty.
	end, tiles := r.until(protocol.TypeScreenFrameEnd)
	if len(tiles) < 4 {
		t.Fatalf("first frame: %d tiles", len(tiles))
	}
	ack(end.Seq)

	// A change outside the damage is not seen; one inside is.
	cp.set(5*w+10, 0x010203)
	cp.set(70*w+130, 0x040506)
	cp.setDamage([]image.Rectangle{image.Rect(130, 70, 131, 71)}, true)
	end, tiles = r.until(protocol.TypeScreenFrameEnd)
	if len(tiles) != 1 || tiles[0].X != 128 || tiles[0].Y != 64 {
		t.Fatalf("damaged frame: %+v", tiles)
	}
	ack(end.Seq)

	// Damage it does not know this time: everything is compared.
	cp.setDamage(nil, false)
	end, tiles = r.until(protocol.TypeScreenFrameEnd)
	if len(tiles) != 1 || tiles[0].X != 0 || tiles[0].Y != 0 {
		t.Fatalf("unknown damage: %+v", tiles)
	}
	ack(end.Seq)
}

func TestEncoderHintSkipsUndamagedTiles(t *testing.T) {
	w, h, px := testScreen()
	e := NewEncoder(w, h)
	// A hint before the first frame changes nothing: it is whole anyway.
	e.Hint(nil)
	if _, n := encodeAll(t, e, px, false, 0); n != 4*3 {
		t.Fatalf("first frame with a hint: %d tiles, want 12", n)
	}
	// Two pixels change, the damage covers only the second one.
	px[5*w+10] = 0x010203
	px[70*w+130] = 0x040506
	e.Hint([]image.Rectangle{image.Rect(129, 69, 131, 71)})
	frames, n := encodeAll(t, e, px, false, 0)
	if n != 1 || len(frames) != 1 || frames[0].X != 128 || frames[0].Y != 64 {
		t.Fatalf("damaged frame: %d tiles, %+v", n, frames)
	}
	// The hint lasts one Encode: without it the first change goes out.
	frames, n = encodeAll(t, e, px, false, 0)
	if n != 1 || frames[0].X != 0 || frames[0].Y != 0 {
		t.Fatalf("after the hint: %d tiles, %+v", n, frames)
	}
	// A full frame ignores the hint.
	e.Hint([]image.Rectangle{})
	if _, n := encodeAll(t, e, px, true, 0); n != 4*3 {
		t.Fatalf("full frame with an empty hint: %d tiles, want 12", n)
	}
	// Damage spanning tiles, or outside the screen, is clipped.
	px[0] = 0x0a0b0c
	px[(h-1)*w+w-1] = 0x0d0e0f
	e.Hint([]image.Rectangle{image.Rect(-5, -5, 1, 1), image.Rect(w-1, h-1, w+50, h+50)})
	if _, n := encodeAll(t, e, px, false, 0); n != 2 {
		t.Fatalf("clipped damage: %d tiles, want 2", n)
	}
}

func TestEncoderRefineIgnoresHint(t *testing.T) {
	w, h, px := testScreen()
	e := NewEncoder(w, h)
	encodeAll(t, e, px, true, 50)
	if !e.Lossy() {
		t.Skip("the test screen has no photographic tile")
	}
	e.Hint(nil)
	var frames int
	if _, err := e.Refine(func(f protocol.Frame) error { frames++; return nil }); err != nil {
		t.Fatal(err)
	}
	if frames == 0 || e.Lossy() {
		t.Fatalf("refine sent %d frames, lossy %v", frames, e.Lossy())
	}
}

func TestCompositeDamageMovesParts(t *testing.T) {
	right := &damaging{fakeCapturer: solid(4, 2, 0xff0000), known: true, rects: []image.Rectangle{image.Rect(1, 0, 2, 1)}}
	left := &damaging{fakeCapturer: solid(2, 3, 0x0000ff), known: true, rects: []image.Rectangle{image.Rect(0, 2, 1, 3)}}
	c := &Composite{Parts: []Part{{X: 0, Y: 0, Capturer: right}, {X: -2, Y: -1, Capturer: left}}}
	w, h, _ := c.Size()
	if err := c.Capture(make([]uint32, w*h), w, h); err != nil {
		t.Fatal(err)
	}
	rects, ok := c.Damage()
	want := []image.Rectangle{image.Rect(3, 1, 4, 2), image.Rect(0, 2, 1, 3)}
	if !ok || len(rects) != 2 || rects[0] != want[0] || rects[1] != want[1] {
		t.Fatalf("damage %v %v, want %v", rects, ok, want)
	}
	// One part that does not know this time: the composite does not either.
	left.known = false
	if _, ok := c.Damage(); ok {
		t.Error("damage known with a part that does not know")
	}
	// One part that is no Damager at all: the same.
	c.Parts[1].Capturer = left.fakeCapturer
	if _, ok := c.Damage(); ok {
		t.Error("damage known with a part that is no Damager")
	}
}
