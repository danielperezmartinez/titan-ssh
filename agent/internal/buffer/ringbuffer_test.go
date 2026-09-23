package buffer

import (
	"bytes"
	"testing"
)

func TestRingTracksOffsetsAndTrims(t *testing.T) {
	r := New(4) // retain only the last 4 bytes
	r.Append([]byte("abc"))
	if r.Head() != 3 || r.Tail() != 0 {
		t.Fatalf("head=%d tail=%d, want 3/0", r.Head(), r.Tail())
	}
	r.Append([]byte("defg")) // total "abcdefg", keep last 4 -> "defg"
	if r.Head() != 7 || r.Tail() != 3 {
		t.Fatalf("head=%d tail=%d, want 7/3", r.Head(), r.Tail())
	}
}

func TestSinceReplaysFromOffset(t *testing.T) {
	r := New(8)
	r.Append([]byte("abcdef"))

	from, out := r.Since(2) // within range
	if from != 2 || !bytes.Equal(out, []byte("cdef")) {
		t.Fatalf("since(2)=%d,%q", from, out)
	}

	from, out = r.Since(6) // at head -> empty
	if from != 6 || len(out) != 0 {
		t.Fatalf("since(head)=%d,%q", from, out)
	}

	r.Append([]byte("ghijk")) // "abcdefghijk", keep last 8 -> "defghijk", tail=3
	from, out = r.Since(0)    // older than tail -> reset to tail
	if from != 3 || !bytes.Equal(out, []byte("defghijk")) {
		t.Fatalf("since(expired)=%d,%q", from, out)
	}
}
