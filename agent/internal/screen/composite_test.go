package screen

import (
	"net"
	"sync"
	"testing"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/protocol"
)

// solid is a fakeCapturer of one color.
func solid(w, h int, col uint32) *fakeCapturer {
	px := make([]uint32, w*h)
	for i := range px {
		px[i] = col
	}
	return &fakeCapturer{w: w, h: h, px: px}
}

func TestCompositeLaysOutMonitors(t *testing.T) {
	// A 4×2 monitor with a 2×3 one on its left, 1 pixel higher.
	left, right := solid(2, 3, 0x0000ff), solid(4, 2, 0xff0000)
	left.cursor = Cursor{X: 1, Y: 2, Visible: true, Serial: 7}
	c := &Composite{Parts: []Part{{X: 0, Y: 0, Capturer: right}, {X: -2, Y: -1, Capturer: left}}}
	w, h, err := c.Size()
	if err != nil || w != 6 || h != 3 {
		t.Fatalf("size %dx%d, %v; want 6x3", w, h, err)
	}
	dst := make([]uint32, w*h)
	for i := range dst {
		dst[i] = 0xdeadbe
	}
	if err := c.Capture(dst, w, h); err != nil {
		t.Fatal(err)
	}
	want := []uint32{
		0x0000ff, 0x0000ff, 0, 0, 0, 0,
		0x0000ff, 0x0000ff, 0xff0000, 0xff0000, 0xff0000, 0xff0000,
		0x0000ff, 0x0000ff, 0xff0000, 0xff0000, 0xff0000, 0xff0000,
	}
	for i := range want {
		if dst[i] != want[i] {
			t.Fatalf("pixel %d,%d = %06x, want %06x", i%w, i/w, dst[i], want[i])
		}
	}

	cur, err := c.Cursor()
	if err != nil || !cur.Visible || cur.X != 1 || cur.Y != 2 {
		t.Errorf("cursor %+v, %v; want at 1,2 on the left monitor", cur, err)
	}
	left.cursor.Visible = false
	right.cursor = Cursor{X: 0, Y: 0, Visible: true, Serial: 7}
	moved, _ := c.Cursor()
	if moved.X != 2 || moved.Y != 1 || moved.Serial == cur.Serial {
		t.Errorf("cursor on the right monitor %+v; want at 2,1 with another serial", moved)
	}

	if i, px, py, ok := c.Locate(3, 2); !ok || i != 0 || px != 1 || py != 1 {
		t.Errorf("Locate(3,2) = %d %d,%d %v; want part 0 at 1,1", i, px, py, ok)
	}
	if i, px, py, ok := c.Locate(0, 0); !ok || i != 1 || px != 0 || py != 0 {
		t.Errorf("Locate(0,0) = %d %d,%d %v; want part 1 at 0,0", i, px, py, ok)
	}
	if _, _, _, ok := c.Locate(4, 0); ok {
		t.Error("Locate found a monitor where there is none")
	}
}

// blockable is a fakeCapturer that can be blocked.
type blockable struct {
	*fakeCapturer
	mu      sync.Mutex
	blocked bool
}

func (b *blockable) Blocked() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.blocked
}

func (b *blockable) set(v bool) {
	b.mu.Lock()
	b.blocked = v
	b.mu.Unlock()
}

func TestServeReportsBlocked(t *testing.T) {
	w, h, px := testScreen()
	cp := &blockable{fakeCapturer: &fakeCapturer{w: w, h: h, px: px}}
	cli, srv := net.Pipe()
	defer cli.Close()
	go func() {
		_ = Serve(srv, &Composite{Parts: []Part{{Capturer: cp}}}, nil,
			Options{Interval: 5 * time.Millisecond, IdleInterval: 5 * time.Millisecond, RefineAfter: time.Hour})
		srv.Close()
	}()
	ack := func(seq uint32) {
		go func() { _, _ = cli.Write(protocol.Encode(protocol.Frame{Type: protocol.TypeScreenAck, Seq: seq})) }()
	}
	if _, err := cli.Write(protocol.Encode(protocol.Frame{Type: protocol.TypeScreenHello, Version: 1})); err != nil {
		t.Fatal(err)
	}
	r := &reader{t: t, c: cli}
	end, _ := r.until(protocol.TypeScreenFrameEnd)
	ack(end.Seq)

	cp.set(true)
	f, _ := r.until(protocol.TypeInputReady)
	if !f.Blocked {
		t.Fatalf("got %+v, want INPUT_READY blocked", f)
	}
	cp.mu.Lock()
	before := cp.captures
	cp.mu.Unlock()
	time.Sleep(50 * time.Millisecond)
	cp.mu.Lock()
	during := cp.captures
	cp.mu.Unlock()
	if during != before {
		t.Errorf("captured %d times while blocked", during-before)
	}

	cp.fakeCapturer.set(0, 0x123456)
	cp.set(false)
	f, _ = r.until(protocol.TypeInputReady)
	if f.Blocked {
		t.Fatalf("got %+v, want INPUT_READY not blocked", f)
	}
	if end, tiles := r.until(protocol.TypeScreenFrameEnd); len(tiles) == 0 {
		t.Errorf("frame %d after unblocking has no tiles", end.Seq)
	}
}
