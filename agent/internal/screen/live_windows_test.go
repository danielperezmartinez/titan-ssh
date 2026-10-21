package screen

import (
	"image"
	"image/color"
	"image/png"
	"os"
	"testing"
)

// TestLiveGDI captures the real desktop, so it only runs on request
// (TITAN_SCREEN_LIVE=1) from a process on an interactive desktop. With
// TITAN_SCREEN_LIVE_PNG set to a path, it also saves the capture and the
// cursor there (the cursor as <path>.cursor.png), to compare by eye.
func TestLiveGDI(t *testing.T) {
	if os.Getenv("TITAN_SCREEN_LIVE") != "1" {
		t.Skip("captures the real desktop; set TITAN_SCREEN_LIVE=1 to run")
	}
	c, err := NewGDI()
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if c.Blocked() {
		t.Fatal("the desktop is not reachable from this process")
	}
	w, h, err := c.Size()
	if err != nil {
		t.Fatal(err)
	}
	vx, vy, _, _ := virtualDesktop()
	t.Logf("virtual desktop %dx%d at %d,%d", w, h, vx, vy)
	px := make([]uint32, w*h)
	if err := c.Capture(px, w, h); err != nil {
		t.Fatal(err)
	}
	colours := map[uint32]bool{}
	for _, p := range px {
		colours[p] = true
		if len(colours) > 16 {
			break
		}
	}
	if len(colours) <= 16 {
		t.Fatalf("the capture has only %d colours: is it empty?", len(colours))
	}
	cur, err := c.Cursor()
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("cursor at %d,%d visible=%v %dx%d hot %d,%d", cur.X, cur.Y, cur.Visible, cur.Width, cur.Height, cur.HotX, cur.HotY)
	if cur.Visible && len(cur.Pixels) != cur.Width*cur.Height {
		t.Fatalf("cursor of %dx%d with %d pixels", cur.Width, cur.Height, len(cur.Pixels))
	}
	if path := os.Getenv("TITAN_SCREEN_LIVE_PNG"); path != "" {
		savePNG(t, path, px, w, h, false)
		if cur.Visible {
			savePNG(t, path+".cursor.png", cur.Pixels, cur.Width, cur.Height, true)
		}
	}
}

func savePNG(t *testing.T, path string, px []uint32, w, h int, alpha bool) {
	t.Helper()
	img := image.NewNRGBA(image.Rect(0, 0, w, h))
	for i, p := range px {
		a := uint8(255)
		if alpha {
			a = uint8(p >> 24)
		}
		img.SetNRGBA(i%w, i/w, color.NRGBA{R: uint8(p >> 16), G: uint8(p >> 8), B: uint8(p), A: a})
	}
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	if err := png.Encode(f, img); err != nil {
		t.Fatal(err)
	}
}
