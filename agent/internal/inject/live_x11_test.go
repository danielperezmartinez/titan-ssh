//go:build linux

package inject

import (
	"os"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/protocol"
)

// TestLiveX11Typing types into a window of the user's X session when
// TITAN_X11_LIVE=1. TITAN_X11_TYPED_FILE names a file a terminal at
// TITAN_X11_TYPE_AT ("x,y") writes what it receives to (for example
// `xterm -u8 -e sh -c 'cat > file'`); the test points there, types a line
// with characters outside the layout and checks the file.
func TestLiveX11Typing(t *testing.T) {
	if os.Getenv("TITAN_X11_LIVE") != "1" {
		t.Skip("set TITAN_X11_LIVE=1 to run against the user's X session")
	}
	inj, err := New()
	if err != nil {
		t.Fatal(err)
	}
	k := inj.(*X11)
	defer k.Restore()
	if err := k.MoveTo(5000, 5000); err != nil {
		t.Fatal(err)
	}
	w, h := k.screen()
	if p, err := k.x.QueryPointer(); err != nil || int(p.X) != w-1 || int(p.Y) != h-1 {
		t.Errorf("pointer not clamped to the screen: %+v %v", p, err)
	}
	if err := k.Move(-10, -20); err != nil {
		t.Fatal(err)
	}
	if p, _ := k.x.QueryPointer(); int(p.X) != w-11 || int(p.Y) != h-21 {
		t.Errorf("relative move ended at %+v", p)
	}
	file, at := os.Getenv("TITAN_X11_TYPED_FILE"), os.Getenv("TITAN_X11_TYPE_AT")
	if file == "" || at == "" {
		t.Skip("no TITAN_X11_TYPED_FILE/TITAN_X11_TYPE_AT to type into")
	}
	xs, ys, _ := strings.Cut(at, ",")
	x, _ := strconv.Atoi(xs)
	y, _ := strconv.Atoi(ys)
	if err := k.MoveTo(x, y); err != nil {
		t.Fatal(err)
	}
	// Click to focus, then type: plain ASCII, Latin-1 (ñ, á), a shifted
	// character and two outside Latin-1 (€, ✓).
	_ = k.Button(protocol.ButtonLeft, true)
	_ = k.Button(protocol.ButtonLeft, false)
	want := "Hola ñandú € ✓ A&B"
	if err := k.Text(want + "\n"); err != nil {
		t.Fatal(err)
	}
	// Ctrl+D at the end ends the terminal's cat.
	defer func() {
		if err := k.Key(protocol.KeyA+3, protocol.ModCtrl, protocol.ActionPress); err != nil {
			t.Error(err)
		}
	}()
	if got := readTyped(t, file); got != want {
		t.Errorf("typed %q, want %q", got, want)
	}
	if len(k.borrowed) == 0 {
		t.Error("no spare keycode was borrowed for €/✓")
	}

	// With a second layout (setxkbmap -layout es,us), type again with it
	// active: the keys must be the second group's, not the first's.
	if !k.xkb || k.x.XkbLockGroup(1) != nil {
		return
	}
	defer k.x.XkbLockGroup(0)
	if g, err := k.x.XkbGroup(); err != nil || g != 1 {
		t.Log("the keymap has one layout: second group not tested")
		return
	}
	if err := os.Truncate(file, 0); err != nil {
		t.Fatal(err)
	}
	want = "ñ; /:@ ú€"
	if err := k.Text(want + "\n"); err != nil {
		t.Fatal(err)
	}
	if got := readTyped(t, file); got != want {
		t.Errorf("with the second layout typed %q, want %q", got, want)
	}
}

// readTyped waits for a line in file and returns it.
func readTyped(t *testing.T, file string) string {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	var got []byte
	for time.Now().Before(deadline) {
		got, _ = os.ReadFile(file)
		if strings.Contains(string(got), "\n") {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	// A truncated file a terminal keeps writing to reads its hole as NULs.
	return strings.TrimRight(strings.TrimLeft(string(got), "\x00"), "\n")
}
