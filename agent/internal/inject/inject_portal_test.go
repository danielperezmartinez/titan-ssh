//go:build linux

package inject

import (
	"fmt"
	"reflect"
	"testing"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/protocol"
)

type fakePortal struct{ calls []string }

func (f *fakePortal) PointerMotion(dx, dy float64) error {
	f.calls = append(f.calls, fmt.Sprintf("motion %g,%g", dx, dy))
	return nil
}
func (f *fakePortal) PointerMotionAbsolute(stream uint32, x, y float64) error {
	f.calls = append(f.calls, fmt.Sprintf("abs %d %g,%g", stream, x, y))
	return nil
}
func (f *fakePortal) PointerButton(code int32, pressed bool) error {
	f.calls = append(f.calls, fmt.Sprintf("button %#x %v", code, pressed))
	return nil
}
func (f *fakePortal) PointerAxisDiscrete(axis uint32, steps int32) error {
	f.calls = append(f.calls, fmt.Sprintf("axis %d %d", axis, steps))
	return nil
}
func (f *fakePortal) KeyboardKeysym(sym int32, pressed bool) error {
	f.calls = append(f.calls, fmt.Sprintf("key %#x %v", sym, pressed))
	return nil
}

func TestPortalInjector(t *testing.T) {
	dev := &fakePortal{}
	// Two monitors: the left one at scale 2 (1920 captured pixels wide).
	locate := func(x, y int) (uint32, float64, float64, bool) {
		if x < 1920 {
			return 40, float64(x) / 2, float64(y) / 2, true
		}
		return 41, float64(x - 1920), float64(y), y < 1080
	}
	p := NewPortal(dev, locate, 2)
	_ = p.MoveTo(100, 50)
	_ = p.MoveTo(2000, 10)
	_ = p.MoveTo(2000, 2000) // between monitors: nothing
	_ = p.Move(4, -2)
	_ = p.Button(protocol.ButtonRight, true)
	_ = p.Button(99, true) // unknown: nothing
	_ = p.Scroll(0, 60)
	_ = p.Scroll(0, 60) // a whole notch up: one step up (negative)
	_ = p.Scroll(-240, 0)
	_ = p.Text("añ\n")
	_ = p.Key(protocol.KeyA+2, protocol.ModCtrl, protocol.ActionPress)
	want := []string{
		"abs 40 50,25", "abs 41 80,10", "motion 2,-1", "button 0x111 true",
		"axis 0 -1", "axis 1 -2",
		"key 0x61 true", "key 0x61 false", "key 0xf1 true", "key 0xf1 false", "key 0xff0d true", "key 0xff0d false",
		"key 0xffe3 true", "key 0x63 true", "key 0x63 false", "key 0xffe3 false",
	}
	if !reflect.DeepEqual(dev.calls, want) {
		t.Fatalf("calls:\n%v\nwant:\n%v", dev.calls, want)
	}
}

func TestPortalSmoothScroll(t *testing.T) {
	dev := &fakePortal{}
	p := NewPortal(dev, nil, 1)
	p.SmoothScroll = func(dx, dy float64) error {
		dev.calls = append(dev.calls, fmt.Sprintf("smooth %g,%g", dx, dy))
		return nil
	}
	_ = p.Scroll(0, 60)   // half a notch up goes at once
	_ = p.Scroll(0, 0)    // nothing
	_ = p.Scroll(-240, 0) // two notches left
	want := []string{"smooth 0,5", "smooth -20,0"}
	if !reflect.DeepEqual(dev.calls, want) {
		t.Fatalf("calls: %v, want %v", dev.calls, want)
	}
}
