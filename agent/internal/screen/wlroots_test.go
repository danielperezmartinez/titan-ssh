//go:build linux

package screen

import (
	"image"
	"testing"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/wayland"
)

func out(lx, ly, lw, lh, pw, ph int) wayland.Output {
	return wayland.Output{Width: pw, Height: ph, Scale: 1, Logical: image.Rect(lx, ly, lx+lw, ly+lh)}
}

func TestLayoutMixedScales(t *testing.T) {
	for _, c := range []struct {
		name   string
		outs   []wayland.Output
		xs, ys []int
	}{
		{"one", []wayland.Output{out(0, 0, 1280, 800, 1280, 800)}, []int{0}, []int{0}},
		{"side by side, the second at scale 2",
			[]wayland.Output{out(0, 0, 1280, 800, 1280, 800), out(1280, 0, 512, 384, 1024, 768)},
			[]int{0, 1280}, []int{0, 0}},
		{"HiDPI on the left",
			[]wayland.Output{out(0, 0, 960, 540, 1920, 1080), out(960, 0, 1280, 800, 1280, 800)},
			[]int{0, 1920}, []int{0, 0}},
		{"a third below the first, beside the HiDPI one",
			[]wayland.Output{out(0, 0, 1024, 600, 1024, 600), out(1024, 0, 512, 384, 1024, 768), out(0, 600, 800, 600, 800, 600)},
			[]int{0, 1024, 0}, []int{0, 0, 600}},
		{"negative positions",
			[]wayland.Output{out(-1280, -200, 1280, 1024, 1280, 1024), out(0, 0, 1920, 1080, 1920, 1080)},
			[]int{0, 1280}, []int{0, 200}},
	} {
		xs, ys := layout(c.outs)
		for i := range c.outs {
			if xs[i] != c.xs[i] || ys[i] != c.ys[i] {
				t.Errorf("%s: output %d at %d,%d, want %d,%d", c.name, i, xs[i], ys[i], c.xs[i], c.ys[i])
			}
		}
	}
}
