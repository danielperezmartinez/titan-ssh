package protocol

import (
	"bytes"
	"encoding/hex"
	"testing"
)

// The exact wire bytes of each input frame. AgentProtocolTest.kt checks the
// same hex strings, which keeps the two codecs in lockstep.
var inputWire = []struct {
	name  string
	frame Frame
	hex   string
}{
	{"move", Frame{Type: TypePointerMove, DX: 12, DY: -3}, "0900000004000cfffd"},
	{"button down", Frame{Type: TypePointerButton, Button: ButtonRight, Pressed: true}, "0a000000020201"},
	{"button up", Frame{Type: TypePointerButton, Button: ButtonLeft}, "0a000000020100"},
	{"scroll", Frame{Type: TypeScroll, DX: -120, DY: 240}, "0b00000004ff8800f0"},
	{"text", Frame{Type: TypeText, Bytes: []byte("ñ€")}, "0c00000005c3b1e282ac"},
	{"key", Frame{Type: TypeKey, Key: KeyA + 2, Mods: ModCtrl | ModShift, Action: ActionPress}, "0d0000000400660300"},
	{"key down", Frame{Type: TypeKey, Key: KeyAlt, Action: ActionDown}, "0d0000000400ca0001"},
	{"ready", Frame{Type: TypeInputReady}, "0e00000000"},
	{"ready blocked", Frame{Type: TypeInputReady, Blocked: true}, "0e0000000101"},
}

func TestInputFramesWireBytes(t *testing.T) {
	for _, c := range inputWire {
		got := hex.EncodeToString(Encode(c.frame))
		if got != c.hex {
			t.Errorf("%s: encoded %s, want %s", c.name, got, c.hex)
		}
		wire, _ := hex.DecodeString(c.hex)
		frames, err := (&Decoder{}).Feed(wire)
		if err != nil || len(frames) != 1 {
			t.Fatalf("%s: decode = %v, %v", c.name, frames, err)
		}
		f := frames[0]
		if f.Type != c.frame.Type || f.DX != c.frame.DX || f.DY != c.frame.DY ||
			f.Button != c.frame.Button || f.Pressed != c.frame.Pressed ||
			f.Key != c.frame.Key || f.Mods != c.frame.Mods || f.Action != c.frame.Action ||
			f.Blocked != c.frame.Blocked || !bytes.Equal(f.Bytes, c.frame.Bytes) {
			t.Errorf("%s: decoded %+v, want %+v", c.name, f, c.frame)
		}
	}
}

func TestShortInputPayloadsAreRejected(t *testing.T) {
	for _, typ := range []Type{TypePointerMove, TypeScroll, TypeKey} {
		wire := Encode(Frame{Type: TypePointerButton}) // a 2-byte payload
		wire[0] = byte(typ)
		if _, err := (&Decoder{}).Feed(wire); err == nil {
			t.Errorf("type %d with a 2-byte payload decoded without error", typ)
		}
	}
	if _, err := (&Decoder{}).Feed([]byte{byte(TypePointerButton), 0, 0, 0, 1, 1}); err == nil {
		t.Error("a 1-byte POINTER_BUTTON decoded without error")
	}
}

func TestInputReadyToleratesLaterFlags(t *testing.T) {
	frames, err := (&Decoder{}).Feed([]byte{byte(TypeInputReady), 0, 0, 0, 1, 0x80})
	if err != nil || len(frames) != 1 || frames[0].Type != TypeInputReady || frames[0].Blocked {
		t.Fatalf("INPUT_READY with a flags byte = %v, %v", frames, err)
	}
}
