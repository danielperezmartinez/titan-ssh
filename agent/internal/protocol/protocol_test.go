package protocol

import (
	"bytes"
	"errors"
	"testing"
)

// roundTrip encodes f and decodes it back through a fresh Decoder.
func roundTrip(t *testing.T, f Frame) Frame {
	t.Helper()
	frames, err := (&Decoder{}).Feed(Encode(f))
	if err != nil {
		t.Fatalf("decode error: %v", err)
	}
	if len(frames) != 1 {
		t.Fatalf("expected 1 frame, got %d", len(frames))
	}
	return frames[0]
}

func TestEveryFrameTypeRoundTrips(t *testing.T) {
	cases := []Frame{
		{Type: TypeHello, SessionID: "titan-abc_123", LastOffset: 4096, Cols: 120, Rows: 40},
		{Type: TypeHelloOK, HeadOffset: 1_000_000, TailOffset: 983_616},
		{Type: TypeData, Offset: 42, Bytes: []byte("hola\x1b[0m mundo")},
		{Type: TypeInput, Bytes: []byte{0x03}},
		{Type: TypeResize, Cols: 80, Rows: 24},
		{Type: TypeReplayFrom, OffsetArg: 512},
		{Type: TypeAck, OffsetArg: 65_536},
		{Type: TypeBye},
	}
	for _, in := range cases {
		got := roundTrip(t, in)
		if got.Type != in.Type {
			t.Errorf("type: got %d want %d", got.Type, in.Type)
		}
		if in.Type == TypeData && (got.Offset != in.Offset || !bytes.Equal(got.Bytes, in.Bytes)) {
			t.Errorf("data frame mismatch: %+v vs %+v", got, in)
		}
	}
}

func TestDecoderReassemblesAcrossChunks(t *testing.T) {
	wire := append(Encode(Frame{Type: TypeData, Offset: 7, Bytes: []byte("chunked")}),
		Encode(Frame{Type: TypeAck, OffsetArg: 14})...)
	dec := &Decoder{}
	var got []Frame
	for _, b := range wire { // one byte at a time
		frames, err := dec.Feed([]byte{b})
		if err != nil {
			t.Fatalf("feed: %v", err)
		}
		got = append(got, frames...)
	}
	if len(got) != 2 || got[0].Type != TypeData || got[1].Type != TypeAck {
		t.Fatalf("unexpected frames: %+v", got)
	}
	if string(got[0].Bytes) != "chunked" || got[1].OffsetArg != 14 {
		t.Fatalf("payload mismatch: %+v", got)
	}
}

func TestUnknownTypeRejected(t *testing.T) {
	_, err := (&Decoder{}).Feed([]byte{99, 0, 0, 0, 0})
	if !errors.Is(err, ErrUnknownType) {
		t.Fatalf("want ErrUnknownType, got %v", err)
	}
}

func TestOversizeRejected(t *testing.T) {
	length := MaxPayload + 1
	header := []byte{byte(TypeData),
		byte(length >> 24), byte(length >> 16), byte(length >> 8), byte(length)}
	_, err := (&Decoder{}).Feed(header)
	if !errors.Is(err, ErrFrameTooLarge) {
		t.Fatalf("want ErrFrameTooLarge, got %v", err)
	}
}
