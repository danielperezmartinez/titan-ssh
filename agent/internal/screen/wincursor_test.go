package screen

import (
	"slices"
	"testing"
)

func TestColourCursorUsesItsAlpha(t *testing.T) {
	colour := []uint32{0x80ff0000, 0x00000000, 0xff00ff00}
	mask := []uint32{0xffffff, 0xffffff, 0}
	if got := colourCursor(colour, mask); !slices.Equal(got, colour) {
		t.Fatalf("got %08x, want the colour bitmap as it is", got)
	}
}

func TestColourCursorWithoutAlphaUsesTheMask(t *testing.T) {
	colour := []uint32{0x123456, 0x654321}
	mask := []uint32{0, 0xffffff}
	want := []uint32{0xff123456, 0}
	if got := colourCursor(colour, mask); !slices.Equal(got, want) {
		t.Fatalf("got %08x, want %08x", got, want)
	}
}

func TestMonochromeCursor(t *testing.T) {
	const (
		set   = 0xffffff
		black = 0xff000000
		white = 0xffffffff
	)
	// One row: black, white, transparent, inverted, transparent.
	and := []uint32{0, 0, set, set, set}
	xor := []uint32{0, set, 0, set, 0}
	got := monochromeCursor(and, xor, 5, 1)
	// The inverted pixel is black, outlined in white where it was transparent.
	want := []uint32{black, white, white, black, white}
	if !slices.Equal(got, want) {
		t.Fatalf("got %08x, want %08x", got, want)
	}
}
