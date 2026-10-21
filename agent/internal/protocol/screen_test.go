package protocol

import (
	"bytes"
	"encoding/hex"
	"reflect"
	"testing"
)

// The wire bytes of each screen frame. AgentProtocolScreenTest.kt checks the
// same hex strings, which keeps the two codecs in lockstep.
var screenGolden = []struct {
	f   Frame
	hex string
}{
	{Frame{Type: TypeScreenHello, Version: 1, Quality: QualityAuto}, "0f0000000201ff"},
	{Frame{Type: TypeScreenSize, Width: 1280, Height: 800}, "100000000405000320"},
	{Frame{Type: TypeScreenTile, X: 64, Y: 128, Width: 64, Height: 32, Encoding: EncodingFill, Bytes: []byte{1, 2, 3}},
		"110000000c004000800040002000010203"},
	{Frame{Type: TypeScreenFrameEnd, Seq: 7}, "120000000400000007"},
	{Frame{Type: TypeScreenAck, Seq: 0x01020304}, "130000000401020304"},
	{Frame{Type: TypeCursorShape, Width: 2, Height: 1, X: 1, Y: 0, Bytes: []byte{9}},
		"1400000009000200010001000009"},
	{Frame{Type: TypeCursorPos, X: -3, Y: 40, Visible: true}, "1500000005fffd002801"},
	{Frame{Type: TypePointerTo, X: 300, Y: 2}, "1600000004012c0002"},
}

func TestScreenFramesGolden(t *testing.T) {
	for _, g := range screenGolden {
		got := hex.EncodeToString(Encode(g.f))
		if got != g.hex {
			t.Errorf("Encode(%d) = %s, want %s", g.f.Type, got, g.hex)
		}
		raw, _ := hex.DecodeString(g.hex)
		frames, err := (&Decoder{}).Feed(raw)
		if err != nil || len(frames) != 1 {
			t.Fatalf("decode %s: %v %v", g.hex, frames, err)
		}
		want := g.f
		if want.Bytes == nil && frames[0].Bytes != nil && len(frames[0].Bytes) == 0 {
			frames[0].Bytes = nil
		}
		if !reflect.DeepEqual(frames[0], want) {
			t.Errorf("decode %s = %+v, want %+v", g.hex, frames[0], want)
		}
	}
}

func TestScreenFramesTooShort(t *testing.T) {
	for _, typ := range []Type{TypeScreenHello, TypeScreenSize, TypeScreenTile, TypeScreenFrameEnd,
		TypeScreenAck, TypeCursorShape, TypeCursorPos, TypePointerTo} {
		if _, err := (&Decoder{}).Feed([]byte{byte(typ), 0, 0, 0, 1, 0}); err == nil {
			t.Errorf("type %d accepted a 1-byte payload", typ)
		}
	}
	// An empty tile body after the header is fine (a zero-length FILL is the
	// client's problem, not the codec's).
	if fr, err := (&Decoder{}).Feed(Encode(Frame{Type: TypeScreenTile, Width: 1, Height: 1})); err != nil ||
		!bytes.Equal(fr[0].Bytes, []byte{}) {
		t.Errorf("empty tile: %v %v", fr, err)
	}
}
