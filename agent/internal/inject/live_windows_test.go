package inject

import (
	"os"
	"testing"
	"time"
	"unsafe"
)

// TestLiveMoveIsExact moves the real cursor, so it only runs on request
// (TITAN_INJECT_LIVE=1) from a process on an interactive desktop. It checks
// that the absolute moves land on the exact pixel, with no acceleration and no
// rounding drift, also when they come in a burst faster than Windows applies
// them.
func TestLiveMoveIsExact(t *testing.T) {
	if os.Getenv("TITAN_INJECT_LIVE") != "1" {
		t.Skip("moves the real cursor; set TITAN_INJECT_LIVE=1 to run")
	}
	inj, err := New()
	if err != nil {
		t.Fatal(err)
	}
	if inj.Blocked() {
		t.Fatal("the input desktop is not reachable from this process")
	}
	cursor := func() (int, int) {
		var p point
		procGetCursorPos.Call(uintptr(unsafe.Pointer(&p)))
		return int(p.X), int(p.Y)
	}
	settleAt := func(x, y int) {
		t.Helper()
		deadline := time.Now().Add(500 * time.Millisecond)
		for {
			gx, gy := cursor()
			if gx == x && gy == y {
				return
			}
			if time.Now().After(deadline) {
				t.Fatalf("the cursor is at %d,%d, want %d,%d", gx, gy, x, y)
			}
			time.Sleep(5 * time.Millisecond)
		}
	}
	move := func(dx, dy int) {
		t.Helper()
		if err := inj.Move(dx, dy); err != nil {
			t.Fatal(err)
		}
	}

	// Start inside the primary monitor, away from its edges.
	move(-100000, -100000)
	time.Sleep(50 * time.Millisecond)
	move(300, 300)
	time.Sleep(50 * time.Millisecond)
	x, y := cursor()

	steps := []struct{ dx, dy int }{{1, 0}, {1, 0}, {0, 1}, {-1, -1}, {37, 0}, {-37, 0}, {0, 120}, {0, -120}, {-1, 0}}
	for _, s := range steps {
		move(s.dx, s.dy)
		x, y = x+s.dx, y+s.dy
		settleAt(x, y)
	}
	// A burst, as a fast swipe sends it: nothing may get lost.
	for range 50 {
		move(3, 2)
	}
	settleAt(x+150, y+100)
	for range 50 {
		move(-3, -2)
	}
	settleAt(x, y)
}
