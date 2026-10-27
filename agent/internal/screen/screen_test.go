package screen

import (
	"bytes"
	"compress/zlib"
	"image/jpeg"
	"io"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/protocol"
)

// canvas is a client's copy of the screen, built from the tiles it gets.
type canvas struct {
	w, h int
	px   []uint32
}

func (c *canvas) apply(t *testing.T, f protocol.Frame) {
	t.Helper()
	switch f.Encoding {
	case protocol.EncodingFill:
		col := uint32(f.Bytes[0])<<16 | uint32(f.Bytes[1])<<8 | uint32(f.Bytes[2])
		for y := f.Y; y < f.Y+f.Height; y++ {
			for x := f.X; x < f.X+f.Width; x++ {
				c.px[y*c.w+x] = col
			}
		}
	case protocol.EncodingZlib:
		zr, err := zlib.NewReader(bytes.NewReader(f.Bytes))
		if err != nil {
			t.Fatal(err)
		}
		raw, err := io.ReadAll(zr)
		if err != nil || len(raw) != f.Width*f.Height*3 {
			t.Fatalf("zlib tile: %d bytes, %v", len(raw), err)
		}
		i := 0
		for y := f.Y; y < f.Y+f.Height; y++ {
			for x := f.X; x < f.X+f.Width; x++ {
				c.px[y*c.w+x] = uint32(raw[i])<<16 | uint32(raw[i+1])<<8 | uint32(raw[i+2])
				i += 3
			}
		}
	case protocol.EncodingJPEG:
		img, err := jpeg.Decode(bytes.NewReader(f.Bytes))
		if err != nil {
			t.Fatal(err)
		}
		if b := img.Bounds(); b.Dx() != f.Width || b.Dy() != f.Height {
			t.Fatalf("jpeg %v for a %dx%d tile", b, f.Width, f.Height)
		}
		for y := 0; y < f.Height; y++ {
			for x := 0; x < f.Width; x++ {
				r, g, b, _ := img.At(x, y).RGBA()
				c.px[(f.Y+y)*c.w+f.X+x] = (r>>8)<<16 | (g>>8)<<8 | b>>8
			}
		}
	default:
		t.Fatalf("encoding %d", f.Encoding)
	}
}

// testScreen is 200x130 (edge tiles of 8 and 2 pixels): a flat background,
// a two-colour "text" block and a noisy "photo" block.
func testScreen() (int, int, []uint32) {
	w, h := 200, 130
	px := make([]uint32, w*h)
	for i := range px {
		px[i] = 0x3a6ea5
	}
	for y := 10; y < 40; y++ {
		for x := 10; x < 150; x++ {
			if (x+y)%3 == 0 {
				px[y*w+x] = 0x000000
			} else {
				px[y*w+x] = 0xffffff
			}
		}
	}
	seed := uint32(1)
	for y := 70; y < 128; y++ {
		for x := 64; x < 128; x++ {
			seed = seed*1664525 + 1013904223
			px[y*w+x] = seed >> 8
		}
	}
	return w, h, px
}

func encodeAll(t *testing.T, e *Encoder, px []uint32, full bool, q int) ([]protocol.Frame, int) {
	t.Helper()
	var frames []protocol.Frame
	n, err := e.Encode(px, full, q, func(f protocol.Frame) error { frames = append(frames, f); return nil })
	if err != nil {
		t.Fatal(err)
	}
	return frames, n
}

func TestEncoderLosslessRoundTrip(t *testing.T) {
	w, h, px := testScreen()
	e := NewEncoder(w, h)
	frames, n := encodeAll(t, e, px, true, 0)
	if n != 4*3 {
		t.Errorf("%d tiles changed, want 12", n)
	}
	c := &canvas{w: w, h: h, px: make([]uint32, w*h)}
	kinds := map[uint8]int{}
	for _, f := range frames {
		kinds[f.Encoding]++
		c.apply(t, f)
	}
	if kinds[protocol.EncodingJPEG] != 0 {
		t.Error("lossless quality sent JPEG")
	}
	if kinds[protocol.EncodingFill] == 0 {
		t.Error("no FILL for the flat tiles")
	}
	for i := range px {
		if c.px[i] != px[i] {
			t.Fatalf("pixel %d = %06x, want %06x", i, c.px[i], px[i])
		}
	}
	// Nothing changed: nothing to send.
	if frames, n := encodeAll(t, e, px, false, 0); n != 0 || len(frames) != 0 {
		t.Errorf("unchanged frame sent %d tiles", n)
	}
	// One pixel changes: one tile goes out.
	px[5*w+190] = 0x123456
	frames, n = encodeAll(t, e, px, false, 0)
	if n != 1 || len(frames) != 1 || frames[0].X != 128 || frames[0].Width != 64 {
		t.Fatalf("one pixel: %d tiles, %+v", n, frames)
	}
	c.apply(t, frames[0])
	if c.px[5*w+190] != 0x123456 {
		t.Error("the change did not arrive")
	}
}

func TestEncoderJPEGForPhotosAndRefine(t *testing.T) {
	w, h, px := testScreen()
	e := NewEncoder(w, h)
	frames, _ := encodeAll(t, e, px, true, 80)
	c := &canvas{w: w, h: h, px: make([]uint32, w*h)}
	jpegs := 0
	for _, f := range frames {
		if f.Encoding == protocol.EncodingJPEG {
			jpegs++
			if f.X != 64 || f.Width != 64 {
				t.Errorf("JPEG tile at %d+%d, want the photo tile", f.X, f.Width)
			}
		}
		c.apply(t, f)
	}
	if jpegs == 0 || !e.Lossy() {
		t.Fatal("the noisy tile did not go as JPEG")
	}
	// The text tiles (two colours) stay exact.
	if c.px[20*w+20] != px[20*w+20] || c.px[21*w+20] != px[21*w+20] {
		t.Error("the text tile is not exact")
	}
	var refined []protocol.Frame
	n, err := e.Refine(func(f protocol.Frame) error { refined = append(refined, f); return nil })
	if err != nil || n == 0 || e.Lossy() {
		t.Fatalf("refine: %d %v lossy=%v", n, err, e.Lossy())
	}
	for _, f := range refined {
		if f.Encoding != protocol.EncodingZlib {
			t.Errorf("refined with encoding %d", f.Encoding)
		}
		c.apply(t, f)
	}
	for i := range px {
		if c.px[i] != px[i] {
			t.Fatalf("after refining, pixel %d = %06x, want %06x", i, c.px[i], px[i])
		}
	}
}

func TestEncoderJoinsNeighbourTiles(t *testing.T) {
	w, h := 640, 64
	px := make([]uint32, w*h)
	for i := range px {
		px[i] = uint32(i%7) * 0x111111 // a few colours everywhere: ZLIB
	}
	frames, n := encodeAll(t, NewEncoder(w, h), px, true, 0)
	if n != 10 || len(frames) != 2 || frames[0].Width != 512 || frames[1].Width != 128 {
		t.Errorf("%d tiles in %d rects: %+v", n, len(frames), frames)
	}
}

// fakeCapturer serves a screen that tests can change.
type fakeCapturer struct {
	mu       sync.Mutex
	w, h     int
	px       []uint32
	captures int
	cursor   Cursor
}

func (f *fakeCapturer) Size() (int, int, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.w, f.h, nil
}

func (f *fakeCapturer) Capture(dst []uint32, w, h int) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.captures++
	copy(dst, f.px)
	return nil
}

func (f *fakeCapturer) Cursor() (Cursor, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.cursor, nil
}

func (f *fakeCapturer) set(i int, v uint32) {
	f.mu.Lock()
	f.px[i] = v
	f.mu.Unlock()
}

type fakeInput struct {
	mu       sync.Mutex
	frames   []protocol.Frame
	released bool
}

func (f *fakeInput) Handle(fr protocol.Frame) error {
	f.mu.Lock()
	f.frames = append(f.frames, fr)
	f.mu.Unlock()
	return nil
}

func (f *fakeInput) Release() {
	f.mu.Lock()
	f.released = true
	f.mu.Unlock()
}

// reader collects the frames a client receives.
type reader struct {
	t   *testing.T
	c   net.Conn
	dec protocol.Decoder
	q   []protocol.Frame
}

func (r *reader) next() protocol.Frame {
	r.t.Helper()
	buf := make([]byte, 64*1024)
	for len(r.q) == 0 {
		_ = r.c.SetReadDeadline(time.Now().Add(3 * time.Second))
		n, err := r.c.Read(buf)
		frames, derr := r.dec.Feed(buf[:n])
		if derr != nil {
			r.t.Fatal(derr)
		}
		r.q = append(r.q, frames...)
		if err != nil && len(r.q) == 0 {
			r.t.Fatalf("read: %v", err)
		}
	}
	f := r.q[0]
	r.q = r.q[1:]
	return f
}

// until reads frames until one of type typ, returning those before it too.
func (r *reader) until(typ protocol.Type) (protocol.Frame, []protocol.Frame) {
	r.t.Helper()
	var before []protocol.Frame
	for {
		f := r.next()
		if f.Type == typ {
			return f, before
		}
		before = append(before, f)
	}
}

func TestServeFlowControlCursorAndInput(t *testing.T) {
	w, h, px := testScreen()
	cp := &fakeCapturer{w: w, h: h, px: px, cursor: Cursor{X: 5, Y: 6, Visible: true, Serial: 1, Width: 1, Height: 1, Pixels: []uint32{0xff000000}}}
	in := &fakeInput{}
	cli, srv := net.Pipe()
	done := make(chan error, 1)
	go func() {
		done <- Serve(srv, cp, in, Options{Interval: 5 * time.Millisecond, IdleInterval: 5 * time.Millisecond, RefineAfter: time.Hour})
		srv.Close()
	}()
	r := &reader{t: t, c: cli}
	send := func(f protocol.Frame) {
		if _, err := cli.Write(protocol.Encode(f)); err != nil {
			t.Fatal(err)
		}
	}
	send(protocol.Frame{Type: protocol.TypeScreenHello, Version: protocol.ScreenVersion, Quality: 0})

	// The cursor goes on its own, so it may come before or among the tiles.
	var size protocol.Frame
	sawShape, sawPos := false, false
	c := &canvas{w: w, h: h, px: make([]uint32, w*h)}
	end, first := r.until(protocol.TypeScreenFrameEnd)
	for _, f := range first {
		switch f.Type {
		case protocol.TypeScreenSize:
			size = f
		case protocol.TypeScreenTile:
			c.apply(t, f)
		case protocol.TypeCursorShape:
			sawShape = true
		case protocol.TypeCursorPos:
			sawPos = sawPos || (f.X == 5 && f.Y == 6)
		}
	}
	if size.Width != w || size.Height != h || !sawShape || !sawPos {
		t.Fatalf("size %+v, cursor shape %v pos %v", size, sawShape, sawPos)
	}
	if end.Seq != 1 || c.px[w*h-1] != px[w*h-1] || c.px[20*w+20] != px[20*w+20] {
		t.Fatalf("first frame seq %d incomplete", end.Seq)
	}

	// Two frames in flight, none acknowledged: the agent stops capturing.
	cp.set(0, 0x010101)
	if end, _ := r.until(protocol.TypeScreenFrameEnd); end.Seq != 2 {
		t.Fatalf("second frame seq %d", end.Seq)
	}
	cp.set(1, 0x020202)
	time.Sleep(60 * time.Millisecond)
	cp.mu.Lock()
	stalled := cp.captures
	cp.mu.Unlock()
	time.Sleep(60 * time.Millisecond)
	cp.mu.Lock()
	if cp.captures != stalled {
		t.Errorf("captured %d more times without credit", cp.captures-stalled)
	}
	cp.mu.Unlock()
	send(protocol.Frame{Type: protocol.TypeScreenAck, Seq: 2})
	if end, _ := r.until(protocol.TypeScreenFrameEnd); end.Seq != 3 {
		t.Fatalf("after the ack, frame seq %d", end.Seq)
	}

	// Input frames reach the injector; the end of the connection releases.
	send(protocol.Frame{Type: protocol.TypePointerTo, X: 10, Y: 20})
	send(protocol.Frame{Type: protocol.TypePointerButton, Button: protocol.ButtonLeft, Pressed: true})
	time.Sleep(30 * time.Millisecond)
	cli.Close()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("Serve did not end with the connection")
	}
	in.mu.Lock()
	defer in.mu.Unlock()
	if len(in.frames) != 2 || in.frames[0].Type != protocol.TypePointerTo || !in.released {
		t.Errorf("input %+v released %v", in.frames, in.released)
	}
}

func TestServeResizeSendsWholeFrame(t *testing.T) {
	w, h, px := testScreen()
	cp := &fakeCapturer{w: w, h: h, px: px}
	cli, srv := net.Pipe()
	defer cli.Close()
	go func() { _ = Serve(srv, cp, nil, Options{Interval: 5 * time.Millisecond}); srv.Close() }()
	r := &reader{t: t, c: cli}
	_, _ = cli.Write(protocol.Encode(protocol.Frame{Type: protocol.TypeScreenHello, Version: 1, Quality: protocol.QualityAuto}))
	r.until(protocol.TypeScreenSize)
	end, _ := r.until(protocol.TypeScreenFrameEnd)
	_, _ = cli.Write(protocol.Encode(protocol.Frame{Type: protocol.TypeScreenAck, Seq: end.Seq}))

	cp.mu.Lock()
	cp.w, cp.h, cp.px = 64, 64, make([]uint32, 64*64)
	cp.mu.Unlock()
	size, _ := r.until(protocol.TypeScreenSize)
	if size.Width != 64 || size.Height != 64 {
		t.Fatalf("new size %+v", size)
	}
	_, tiles := r.until(protocol.TypeScreenFrameEnd)
	if len(tiles) != 1 || tiles[0].Encoding != protocol.EncodingFill || tiles[0].Width != 64 {
		t.Errorf("after the resize: %+v", tiles)
	}
}

func TestServeNeedsHello(t *testing.T) {
	cli, srv := net.Pipe()
	defer cli.Close()
	err := Serve(srv, &fakeCapturer{w: 1, h: 1, px: []uint32{0}}, nil, Options{HelloTimeout: 50 * time.Millisecond})
	if err != ErrNoHello {
		t.Errorf("err = %v", err)
	}
}

func TestUnpremultiply(t *testing.T) {
	if got := unpremultiply(0x80404040); got != 0x80808080 {
		t.Errorf("half alpha: %08x", got)
	}
	if unpremultiply(0) != 0 || unpremultiply(0xff123456) != 0xff123456 {
		t.Error("opaque or transparent changed")
	}
}
