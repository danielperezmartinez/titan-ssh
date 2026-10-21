//go:build unix

package x11

import (
	"os"
	"testing"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/graphical"
)

// TestLiveDisplay runs against the user's real X session when
// TITAN_X11_LIVE=1 (for example inside the tools/test-x11 container, as the
// user demo): it finds the session, reads the screen and the cursor, and
// moves the pointer with XTEST.
func TestLiveDisplay(t *testing.T) {
	if os.Getenv("TITAN_X11_LIVE") != "1" {
		t.Skip("set TITAN_X11_LIVE=1 to run against the user's X session")
	}
	s, err := graphical.Find()
	if err != nil {
		t.Fatal(err)
	}
	if s.Kind != graphical.X11 {
		t.Skip("not an X11 session: " + s.Describe())
	}
	d, err := ParseDisplay(s.Display)
	if err != nil {
		t.Fatal(err)
	}
	x, err := Dial(d, s.Xauthority)
	if err != nil {
		t.Fatal(err)
	}
	defer x.Close()
	g, err := x.GetGeometry(x.Setup.Root)
	if err != nil {
		t.Fatal(err)
	}
	w, h := int(g.Width), int(g.Height)
	t.Logf("display %s %dx%d depth %d vendor %q", s.Display, w, h, g.Depth, x.Setup.Vendor)
	px := make([]uint32, w*h)
	if err := x.GetImage(0, 0, w, h, px); err != nil {
		t.Fatal(err)
	}
	colors := map[uint32]int{}
	for _, p := range px {
		colors[p]++
	}
	t.Logf("%d distinct colors; corner pixel %06x", len(colors), px[len(px)-1])
	if len(colors) < 2 {
		t.Error("the screen has a single color")
	}
	if err := x.XTestInit(); err != nil {
		t.Fatal(err)
	}
	if err := x.FakeInput(MotionNotify, 0, 123, 77); err != nil {
		t.Fatal(err)
	}
	if err := x.Sync(); err != nil {
		t.Fatal(err)
	}
	p, err := x.QueryPointer()
	if err != nil || p.X != 123 || p.Y != 77 {
		t.Errorf("pointer at %+v, %v; want 123,77", p, err)
	}
	if err := x.XFixesInit(); err != nil {
		t.Fatal(err)
	}
	c, err := x.GetCursorImage()
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("cursor %dx%d hot %d,%d at %d,%d serial %d", c.Width, c.Height, c.HotX, c.HotY, c.X, c.Y, c.Serial)
	if c.X != 123 || c.Y != 77 || len(c.Pixels) == 0 {
		t.Errorf("cursor = %+v", c)
	}
	m, err := x.GetKeyboardMapping()
	if err != nil || m.PerCode == 0 || len(m.Syms) == 0 {
		t.Fatalf("keyboard mapping %d per code, %d syms, %v", m.PerCode, len(m.Syms), err)
	}
}
