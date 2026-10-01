package inject

import (
	"testing"
	"unsafe"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/protocol"
)

func TestInputStructsMatchTheWin32Layout(t *testing.T) {
	want := uintptr(28) // 32-bit Windows
	if unsafe.Sizeof(uintptr(0)) == 8 {
		want = 40
	}
	if got := unsafe.Sizeof(mouseInput{}); got != want {
		t.Errorf("sizeof(mouseInput) = %d, want %d", got, want)
	}
	if got := unsafe.Sizeof(keyInput{}); got != want {
		t.Errorf("sizeof(keyInput) = %d, want %d", got, want)
	}
	// The union starts pointer-aligned, as in C.
	if got, align := unsafe.Offsetof(mouseInput{}.mi), unsafe.Alignof(uintptr(0)); got != align {
		t.Errorf("offset of the mouse arm = %d, want %d", got, align)
	}
}

func TestNormalizeLandsOnTheSamePixel(t *testing.T) {
	for _, size := range []int{1080, 1920, 2560, 3840, 5760} {
		for px := 0; px < size; px++ {
			n := normalize(px, size)
			if n < 0 || n > 65535 {
				t.Fatalf("size %d px %d: normalized %d out of range", size, px, n)
			}
			if back := n * size / 65536; back != px {
				t.Fatalf("size %d px %d: normalized %d maps back to %d", size, px, n, back)
			}
		}
	}
}

func TestEveryProtocolKeyHasAVirtualKey(t *testing.T) {
	keys := []uint16{}
	for k := protocol.KeyEnter; k <= protocol.KeyMediaPrevious; k++ {
		keys = append(keys, k)
	}
	for k := protocol.KeyA; k <= protocol.KeyZ; k++ {
		keys = append(keys, k)
	}
	for k := protocol.Key0; k <= protocol.Key9; k++ {
		keys = append(keys, k)
	}
	keys = append(keys, protocol.KeyShift, protocol.KeyCtrl, protocol.KeyAlt, protocol.KeyMeta)
	for _, k := range keys {
		if _, ok := virtualKey(k); !ok {
			t.Errorf("protocol key %d has no virtual key", k)
		}
	}
	if vk, _ := virtualKey(protocol.KeyA + 2); vk != 'C' {
		t.Errorf("KeyA+2 = %#x, want 'C'", vk)
	}
	if vk, _ := virtualKey(protocol.KeyF12); vk != 0x7B {
		t.Errorf("F12 = %#x, want 0x7B", vk)
	}
}
