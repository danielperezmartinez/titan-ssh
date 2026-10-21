package dbus

import (
	"encoding/binary"
	"reflect"
	"testing"
)

func TestMessageRoundTrip(t *testing.T) {
	m := &Message{
		Type: TypeMethodCall, Serial: 7, Path: "/org/freedesktop/portal/desktop",
		Interface: "org.freedesktop.portal.RemoteDesktop", Member: "SelectDevices",
		Destination: "org.freedesktop.portal.Desktop",
		Signature:   "oa{sv}(ii)adtbayas",
		Body: []any{
			ObjectPath("/org/freedesktop/portal/desktop/session/1_2/t"),
			map[string]Variant{
				"handle_token":  MakeVariant("t1"),
				"types":         MakeVariant(uint32(3)),
				"persist_mode":  MakeVariant(uint32(2)),
				"multiple":      MakeVariant(true),
				"nested":        MakeVariant(map[string]Variant{"x": MakeVariant(int32(-5))}),
				"restore_token": MakeVariant("abc"),
			},
			[]any{int32(-1920), int32(1080)},
			[]any{1.5, -2.25},
			uint64(1 << 40),
			false,
			[]byte{1, 2, 3},
			[]string{"yes", "no"},
		},
	}
	data, fds, err := m.marshal()
	if err != nil {
		t.Fatal(err)
	}
	if len(fds) != 0 {
		t.Fatalf("fds = %v", fds)
	}
	if n, err := frameLength(data); err != nil || n != len(data) {
		t.Fatalf("frameLength = %d, %v; want %d", n, err, len(data))
	}
	got, used, err := unmarshal(data, nil)
	if err != nil {
		t.Fatal(err)
	}
	if used != 0 || got.Member != m.Member || got.Path != m.Path || got.Interface != m.Interface || got.Destination != m.Destination || got.Serial != 7 {
		t.Fatalf("header = %+v", got)
	}
	opts := got.Body[1].(map[string]any)
	if v := opts["types"].(Variant); v.Sig != "u" || v.Value != uint32(3) {
		t.Fatalf("types = %#v", v)
	}
	nested := opts["nested"].(Variant).Value.(map[string]any)
	if nested["x"].(Variant).Value != int32(-5) {
		t.Fatalf("nested = %#v", nested)
	}
	if !reflect.DeepEqual(got.Body[2], []any{int32(-1920), int32(1080)}) {
		t.Fatalf("struct = %#v", got.Body[2])
	}
	if !reflect.DeepEqual(got.Body[3], []any{1.5, -2.25}) || got.Body[4] != uint64(1<<40) || got.Body[5] != false {
		t.Fatalf("body = %#v", got.Body)
	}
	if !reflect.DeepEqual(got.Body[6], []byte{1, 2, 3}) || !reflect.DeepEqual(got.Body[7], []any{"yes", "no"}) {
		t.Fatalf("arrays = %#v %#v", got.Body[6], got.Body[7])
	}
}

func TestDescriptorsAndBigEndian(t *testing.T) {
	m := &Message{Type: TypeMethodReturn, ReplySerial: 3, Signature: "h", Body: []any{FD(9)}}
	data, fds, err := m.marshal()
	if err != nil || len(fds) != 1 || fds[0] != 9 {
		t.Fatalf("marshal: %v %v", fds, err)
	}
	got, used, err := unmarshal(data, []int{42, 43})
	if err != nil || used != 1 || got.Files[0] != 42 || got.Body[0] != FDIndex(0) {
		t.Fatalf("unmarshal: %+v %d %v", got, used, err)
	}

	// A big-endian signal, as another machine's sender would write it.
	e := &encoder{order: binary.BigEndian}
	body := &encoder{order: binary.BigEndian}
	_ = body.encode("u", uint32(1))
	_ = body.encode("a{sv}", map[string]Variant{"restore_token": MakeVariant("tok")})
	e.buf = append(e.buf, 'B', TypeSignal, 0, 1)
	e.u32(uint32(len(body.buf)))
	e.u32(5)
	_ = e.encode("a(yv)", []any{
		[]any{uint8(fieldPath), Variant{"o", ObjectPath("/r")}},
		[]any{uint8(fieldMember), Variant{"s", "Response"}},
		[]any{uint8(fieldSignature), Variant{"g", Signature("ua{sv}")}},
	})
	e.align(8)
	raw := append(e.buf, body.buf...)
	sig, _, err := unmarshal(raw, nil)
	if err != nil {
		t.Fatal(err)
	}
	if sig.Member != "Response" || sig.Body[0] != uint32(1) || sig.Body[1].(map[string]any)["restore_token"].(Variant).Value != "tok" {
		t.Fatalf("signal = %+v", sig)
	}
}

func TestBadInput(t *testing.T) {
	m := &Message{Type: TypeSignal, Path: "/a", Member: "X", Signature: "s", Body: []any{"hello"}}
	data, _, _ := m.marshal()
	for i := 16; i < len(data); i++ {
		// Truncations must fail cleanly, never panic.
		_, _, _ = unmarshal(data[:i], nil)
	}
	if _, _, err := unmarshal(append([]byte{'x'}, data[1:]...), nil); err == nil {
		t.Fatal("bad endianness accepted")
	}
	bad := &Message{Type: TypeSignal, Signature: "u", Body: []any{"no"}}
	if _, _, err := bad.marshal(); err == nil {
		t.Fatal("wrong type accepted")
	}
}
