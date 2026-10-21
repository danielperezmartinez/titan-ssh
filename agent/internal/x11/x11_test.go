package x11

import (
	"bytes"
	"encoding/binary"
	"io"
	"net"
	"testing"
)

func TestParseDisplay(t *testing.T) {
	for in, want := range map[string]string{":0": "0", ":1.0": "1", "unix:12": "12"} {
		d, err := ParseDisplay(in)
		if err != nil || d.Number != want {
			t.Errorf("ParseDisplay(%q) = %v, %v; want %s", in, d, err, want)
		}
	}
	for _, bad := range []string{"", "localhost:10.0", "host:0", ":", ":x"} {
		if _, err := ParseDisplay(bad); err == nil {
			t.Errorf("ParseDisplay(%q) accepted", bad)
		}
	}
}

func xauthEntry(fam uint16, addr, num, name string, data []byte) []byte {
	var b bytes.Buffer
	_ = binary.Write(&b, binary.BigEndian, fam)
	for _, f := range [][]byte{[]byte(addr), []byte(num), []byte(name), data} {
		_ = binary.Write(&b, binary.BigEndian, uint16(len(f)))
		b.Write(f)
	}
	return b.Bytes()
}

func TestXauthorityPicksThisHostsCookie(t *testing.T) {
	var file []byte
	file = append(file, xauthEntry(familyLocal, "other", "1", "MIT-MAGIC-COOKIE-1", []byte("other-host"))...)
	file = append(file, xauthEntry(familyLocal, "box", "0", "MIT-MAGIC-COOKIE-1", []byte("display-0"))...)
	file = append(file, xauthEntry(familyLocal, "box", "1", "XDM-AUTHORIZATION-1", []byte("wrong-kind"))...)
	file = append(file, xauthEntry(familyLocal, "box", "1", "MIT-MAGIC-COOKIE-1", []byte("right"))...)
	entries, err := ParseXauthority(bytes.NewReader(file))
	if err != nil || len(entries) != 4 {
		t.Fatalf("ParseXauthority = %d entries, %v", len(entries), err)
	}
	if c, ok := pickCookie(entries, "1", "box"); !ok || string(c) != "right" {
		t.Errorf("cookie for box:1 = %q, %v", c, ok)
	}
	// Another hostname (a renamed machine): any local cookie for the display.
	if c, ok := pickCookie(entries, "1", "renamed"); !ok || string(c) != "other-host" {
		t.Errorf("fallback cookie = %q, %v", c, ok)
	}
	if _, ok := pickCookie(entries, "7", "box"); ok {
		t.Error("found a cookie for a display without one")
	}
	if _, err := ParseXauthority(bytes.NewReader(file[:len(file)-3])); err == nil {
		t.Error("a truncated file parsed")
	}
}

// fakeServer answers the connection setup and then one GetImage of 2x1 pixels.
func fakeServer(t *testing.T, srv net.Conn, wantCookie string) {
	t.Helper()
	head := make([]byte, 12)
	if _, err := io.ReadFull(srv, head); err != nil {
		t.Error(err)
		return
	}
	n, d := int(le.Uint16(head[6:])), int(le.Uint16(head[8:]))
	auth := make([]byte, n+pad4(n)+d+pad4(d))
	if _, err := io.ReadFull(srv, auth); err != nil {
		t.Error(err)
		return
	}
	if got := string(auth[n+pad4(n) : n+pad4(n)+d]); got != wantCookie {
		reason := "bad cookie"
		reply := make([]byte, 8+12)
		reply[1] = byte(len(reason))
		le.PutUint16(reply[6:], 3)
		copy(reply[8:], reason)
		srv.Write(reply)
		return
	}
	// Setup body: fixed part, vendor "test", one format, one screen with one
	// depth and one visual.
	body := make([]byte, 32+4+8+40+8+24)
	le.PutUint16(body[16:], 4)      // vendor length
	le.PutUint16(body[18:], 0xffff) // max request length
	body[20], body[21] = 1, 1       // screens, formats
	body[26], body[27] = 8, 255
	copy(body[32:], "test")
	f := 36
	body[f], body[f+1], body[f+2] = 24, 32, 32
	s := f + 8
	le.PutUint32(body[s:], 0x100)   // root
	le.PutUint16(body[s+20:], 2)    // width
	le.PutUint16(body[s+22:], 1)    // height
	le.PutUint32(body[s+32:], 0x21) // root visual
	body[s+38], body[s+39] = 24, 1
	dp := s + 40
	body[dp] = 24
	le.PutUint16(body[dp+2:], 1)
	v := dp + 8
	le.PutUint32(body[v:], 0x21)
	body[v+4] = 4 // TrueColor
	le.PutUint32(body[v+8:], 0xff0000)
	le.PutUint32(body[v+12:], 0xff00)
	le.PutUint32(body[v+16:], 0xff)
	reply := make([]byte, 8)
	reply[0] = 1
	le.PutUint16(reply[2:], 11)
	le.PutUint16(reply[6:], uint16(len(body)/4))
	srv.Write(append(reply, body...))

	req := make([]byte, 20)
	if _, err := io.ReadFull(srv, req); err != nil || req[0] != opGetImage {
		t.Errorf("expected GetImage, got %v %v", req, err)
		return
	}
	// An unrelated event first, which the client must skip.
	ev := make([]byte, 32)
	ev[0] = 12
	srv.Write(ev)
	img := make([]byte, 32+8)
	img[0], img[1] = 1, 24
	le.PutUint16(img[2:], 1) // sequence
	le.PutUint32(img[4:], 2) // 8 bytes of data
	le.PutUint32(img[32:], 0xAA112233)
	le.PutUint32(img[36:], 0x00445566)
	srv.Write(img)
}

func TestConnSetupAndGetImage(t *testing.T) {
	cli, srv := net.Pipe()
	defer srv.Close()
	go fakeServer(t, srv, "cookie")
	x, err := NewConn(cli, "MIT-MAGIC-COOKIE-1", []byte("cookie"))
	if err != nil {
		t.Fatal(err)
	}
	defer x.Close()
	if x.Setup.Vendor != "test" || x.Setup.Root != 0x100 || x.Setup.Width != 2 || x.Setup.RootDepth != 24 {
		t.Fatalf("setup = %+v", x.Setup)
	}
	px := make([]uint32, 2)
	if err := x.GetImage(0, 0, 2, 1, px); err != nil {
		t.Fatal(err)
	}
	if px[0] != 0x112233 || px[1] != 0x445566 {
		t.Errorf("pixels = %06x %06x", px[0], px[1])
	}
}

func TestConnRefusedCookie(t *testing.T) {
	cli, srv := net.Pipe()
	defer srv.Close()
	go fakeServer(t, srv, "cookie")
	if _, err := NewConn(cli, "MIT-MAGIC-COOKIE-1", []byte("nope")); err == nil ||
		!bytes.Contains([]byte(err.Error()), []byte("bad cookie")) {
		t.Errorf("err = %v", err)
	}
}
