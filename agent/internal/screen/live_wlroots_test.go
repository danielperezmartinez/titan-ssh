//go:build linux

package screen

import (
	"image"
	"image/png"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/inject"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/protocol"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/wayland"
)

// These tests run inside tools/test-wlroots (sway headless, two outputs, the
// second at scale 2), as the user demo, with TITAN_WAYLAND_LIVE=1; see its
// README. They are skipped anywhere else.
func liveWlroots(t *testing.T) (*wayland.Conn, *inject.Wayland, *Wlroots) {
	t.Helper()
	if os.Getenv("TITAN_WAYLAND_LIVE") != "1" {
		t.Skip("set TITAN_WAYLAND_LIVE=1 inside tools/test-wlroots")
	}
	c, err := wayland.Dial(wayland.SocketPath(os.Getenv("XDG_RUNTIME_DIR"), os.Getenv("WAYLAND_DISPLAY")))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	inj, err := inject.NewWayland(c)
	if err != nil {
		t.Fatal(err)
	}
	var pointer uint32
	if os.Getenv("TITAN_WAYLAND_PAINT_CURSOR") != "1" {
		pointer = inj.Pointer()
	}
	w, err := NewWlroots(c, pointer)
	if err != nil {
		t.Fatal(err)
	}
	inj.SetTarget(w.PointerTarget)
	t.Logf("capture through %s", w.Protocol())
	return c, inj, w
}

func grab(t *testing.T, w *Wlroots) ([]uint32, int, int) {
	t.Helper()
	sw, sh, err := w.Size()
	if err != nil {
		t.Fatal(err)
	}
	px := make([]uint32, sw*sh)
	if err := w.Capture(px, sw, sh); err != nil {
		t.Fatal(err)
	}
	return px, sw, sh
}

// grim reads an output through the compositor's own screenshot tool.
func grim(t *testing.T, output string) image.Image {
	t.Helper()
	f := t.TempDir() + "/shot.png"
	if out, err := exec.Command("grim", "-o", output, f).CombinedOutput(); err != nil {
		t.Fatalf("grim: %v: %s", err, out)
	}
	r, err := os.Open(f)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	img, err := png.Decode(r)
	if err != nil {
		t.Fatal(err)
	}
	return img
}

// matching counts the pixels of img equal to the w-wide capture at ox, oy.
func matching(img image.Image, px []uint32, w, ox, oy int) (same, total int) {
	b := img.Bounds()
	for y := b.Min.Y; y < b.Max.Y; y++ {
		for x := b.Min.X; x < b.Max.X; x++ {
			r, g, bl, _ := img.At(x, y).RGBA()
			want := (r>>8)<<16 | (g>>8)<<8 | bl>>8
			if px[(oy+y)*w+ox+x] == want {
				same++
			}
			total++
		}
	}
	return
}

func TestLiveWlrootsCapturesBothOutputs(t *testing.T) {
	_, _, w := liveWlroots(t)
	px, sw, sh := grab(t, w)
	// HEADLESS-1 is 1280x800 at scale 1; HEADLESS-2 1024x768 at scale 2, to
	// its right: side by side with their own pixels.
	if sw != 1280+1024 || sh != 800 {
		t.Fatalf("desktop %dx%d, want 2304x800", sw, sh)
	}
	for _, o := range []struct {
		name   string
		ox, oy int
	}{{"HEADLESS-1", 0, 0}, {"HEADLESS-2", 1280, 0}} {
		shot := grim(t, o.name)
		px, sw, _ = grab(t, w)
		same, total := matching(shot, px, sw, o.ox, o.oy)
		// The clock may tick between the two shots.
		if float64(same) < 0.97*float64(total) {
			t.Errorf("%s: %d of %d pixels as grim sees them", o.name, same, total)
		}
	}
}

func TestLiveWlrootsDamage(t *testing.T) {
	_, _, w := liveWlroots(t)
	grab(t, w)
	if d, ok := w.Damage(); !ok || len(d) == 0 {
		t.Fatalf("first damage %v %v, want the whole image", d, ok)
	}
	// The clock ticks every second: some damage, far from all of it.
	time.Sleep(1500 * time.Millisecond)
	grab(t, w)
	d, ok := w.Damage()
	if !ok || len(d) == 0 {
		t.Fatalf("damage after a tick %v %v", d, ok)
	}
	area := 0
	for _, r := range d {
		area += r.Dx() * r.Dy()
	}
	if area >= 2304*800/2 {
		t.Errorf("a clock tick damaged %d pixels: %v", area, d)
	}
	t.Logf("a tick damaged %d pixels in %d rectangles", area, len(d))
}

func TestLiveWlrootsPointerAndCursor(t *testing.T) {
	c, inj, w := liveWlroots(t)
	grab(t, w)
	for _, p := range []image.Point{{300, 200}, {1280 + 600, 500}, {1280 + 10, 10}} {
		if err := inj.MoveTo(p.X, p.Y); err != nil {
			t.Fatal(err)
		}
		if err := c.Roundtrip(); err != nil {
			t.Fatal(err)
		}
		if w.pointer == 0 {
			// The cursor is painted into the image: it damages where it lands.
			if !damagedNear(t, w, p) {
				t.Errorf("pointer to %v: no damage there", p)
			}
			continue
		}
		deadline := time.Now().Add(2 * time.Second)
		var cur Cursor
		for time.Now().Before(deadline) {
			grab(t, w)
			cur, _ = w.Cursor()
			if cur.Visible && cur.X == p.X && cur.Y == p.Y && len(cur.Pixels) > 0 {
				break
			}
			time.Sleep(50 * time.Millisecond)
		}
		// On the scale 2 output the pointer lands on even pixels.
		if !cur.Visible || abs(cur.X-p.X) > 1 || abs(cur.Y-p.Y) > 1 || len(cur.Pixels) == 0 {
			t.Errorf("pointer to %v: cursor %+v (%d pixels)", p, cur, len(cur.Pixels))
		}
	}
	// Relative moves go through too.
	if err := inj.Move(-20, -5); err != nil {
		t.Fatal(err)
	}
	if err := inj.Button(protocol.ButtonLeft, true); err != nil {
		t.Fatal(err)
	}
	if err := inj.Button(protocol.ButtonLeft, false); err != nil {
		t.Fatal(err)
	}
	if err := inj.Scroll(0, 240); err != nil {
		t.Fatal(err)
	}
	if err := c.Roundtrip(); err != nil {
		t.Fatalf("after move, click and scroll: %v", err)
	}
}

func TestLiveWlrootsTypes(t *testing.T) {
	c, inj, _ := liveWlroots(t)
	out := t.TempDir() + "/typed"
	if b, err := exec.Command("swaymsg", "exec", "foot --title typed sh -c 'cat > "+out+"'").CombinedOutput(); err != nil {
		t.Fatalf("swaymsg: %v: %s", err, b)
	}
	defer func() { _ = exec.Command("swaymsg", "[title=typed] kill").Run() }()
	// Wait for the window to take the focus.
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		b, _ := exec.Command("swaymsg", "-t", "get_tree").Output()
		if strings.Contains(string(b), `"name": "typed"`) && focused(b, "typed") {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	time.Sleep(300 * time.Millisecond)
	text := "hola Mundo ñ€ 1+1=2 «ok»"
	if err := inj.Text(text + "\n"); err != nil {
		t.Fatal(err)
	}
	// Shift+a by key: an uppercase A; then Enter.
	if err := inj.Key(protocol.KeyA, protocol.ModShift, protocol.ActionPress); err != nil {
		t.Fatal(err)
	}
	if err := inj.Key(protocol.KeyEnter, 0, protocol.ActionPress); err != nil {
		t.Fatal(err)
	}
	if err := c.Roundtrip(); err != nil {
		t.Fatal(err)
	}
	var got string
	deadline = time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		b, _ := os.ReadFile(out)
		if got = string(b); strings.Count(got, "\n") >= 2 {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	if want := text + "\nA\n"; got != want {
		t.Errorf("typed %q, want %q", got, want)
	}
}

// focused reports whether the focused node of a get_tree is named name.
func focused(tree []byte, name string) bool {
	s := string(tree)
	i := strings.Index(s, `"name": "`+name+`"`)
	if i < 0 {
		return false
	}
	// The node's "focused" comes a little before its name in sway's output.
	start := strings.LastIndex(s[:i], `"id":`)
	return start >= 0 && strings.Contains(s[start:i], `"focused": true`)
}

// damagedNear waits for damage at p.
func damagedNear(t *testing.T, w *Wlroots, p image.Point) bool {
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		grab(t, w)
		d, _ := w.Damage()
		for _, r := range d {
			if p.In(r.Inset(-2)) {
				return true
			}
		}
		time.Sleep(50 * time.Millisecond)
	}
	return false
}

func abs(v int) int {
	if v < 0 {
		return -v
	}
	return v
}

func swaymsg(t *testing.T, args ...string) {
	t.Helper()
	if b, err := exec.Command("swaymsg", args...).CombinedOutput(); err != nil {
		t.Fatalf("swaymsg %v: %v: %s", args, err, b)
	}
}

// waitSize waits for the desktop to become w×h.
func waitSize(t *testing.T, cap *Wlroots, w, h int) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	var sw, sh int
	for time.Now().Before(deadline) {
		px, gw, gh := grab(t, cap)
		sw, sh = gw, gh
		_ = px
		if sw == w && sh == h {
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
	t.Fatalf("desktop %dx%d, want %dx%d", sw, sh, w, h)
}

func TestLiveWlrootsFollowsOutputChanges(t *testing.T) {
	_, _, w := liveWlroots(t)
	waitSize(t, w, 2304, 800)
	// A new resolution on the first monitor moves the second one.
	swaymsg(t, "output", "HEADLESS-1", "resolution", "1024x600")
	defer swaymsg(t, "output", "HEADLESS-1", "resolution", "1280x800")
	swaymsg(t, "output", "HEADLESS-2", "position", "1024", "0")
	defer swaymsg(t, "output", "HEADLESS-2", "position", "1280", "0")
	waitSize(t, w, 1024+1024, 768)

	// A third monitor plugged in below the first one, then unplugged.
	before := map[string]bool{}
	for _, o := range outputNames(t) {
		before[o] = true
	}
	swaymsg(t, "create_output")
	var added string
	for _, o := range outputNames(t) {
		if !before[o] {
			added = o
		}
	}
	if added == "" {
		t.Fatal("create_output added no output")
	}
	unplugged := false
	t.Cleanup(func() {
		if !unplugged {
			_ = exec.Command("swaymsg", "output", added, "unplug").Run()
		}
	})
	swaymsg(t, "output", added, "resolution", "800x600", "position", "0", "600", "scale", "1")
	waitSize(t, w, 2048, 1200)
	swaymsg(t, "output", added, "unplug")
	unplugged = true
	waitSize(t, w, 2048, 768)
}

func outputNames(t *testing.T) []string {
	t.Helper()
	b, err := exec.Command("swaymsg", "-t", "get_outputs", "-r").Output()
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, part := range strings.Split(string(b), `"name": "`)[1:] {
		names = append(names, part[:strings.Index(part, `"`)])
	}
	return names
}
