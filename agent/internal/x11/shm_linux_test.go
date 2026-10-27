package x11

import (
	"io"
	"net"
	"testing"

	"golang.org/x/sys/unix"
)

// The fake screen of fakeSetup: 2x1 pixels.
var shmScreen = []uint32{0x112233, 0x445566}

const shmOpcode = 130

// fakeShmServer is an X server with MIT-SHM (if hasShm) on this machine: it
// attaches the client's segment and writes ShmGetImage's pixels into it,
// unless foreign, where it acts like a server in another IPC namespace: it
// cannot find the segment and answers its requests with BadShmSeg, as Xvfb
// does. It serves until the connection closes.
func fakeShmServer(t *testing.T, srv net.Conn, hasShm, foreign bool) {
	if !fakeSetup(t, srv, "") {
		return
	}
	// Writes go out in order from their own goroutine, as a socket's buffer
	// would let them: net.Pipe blocks a writer until the other side reads.
	out := make(chan []byte, 64)
	defer close(out)
	go func() {
		for b := range out {
			srv.Write(b)
		}
	}()
	var seq uint16
	var mem []byte
	defer func() {
		if mem != nil {
			unix.SysvShmDetach(mem)
		}
	}()
	reply := func(extra []byte, b1 byte, fill func(r []byte)) {
		r := make([]byte, 32+len(extra))
		r[0], r[1] = 1, b1
		le.PutUint16(r[2:], seq)
		le.PutUint32(r[4:], uint32(len(extra)/4))
		if fill != nil {
			fill(r)
		}
		copy(r[32:], extra)
		out <- r
	}
	badSeg := func(minor uint16, seg uint32) {
		e := make([]byte, 32)
		e[1] = 128 // the first MIT-SHM error, BadShmSeg
		le.PutUint16(e[2:], seq)
		le.PutUint32(e[4:], seg)
		le.PutUint16(e[8:], minor)
		e[10] = shmOpcode
		out <- e
	}
	head := make([]byte, 4)
	for {
		if _, err := io.ReadFull(srv, head); err != nil {
			return
		}
		body := make([]byte, int(le.Uint16(head[2:]))*4-4)
		if _, err := io.ReadFull(srv, body); err != nil {
			return
		}
		seq++
		switch {
		case head[0] == opQueryExtension:
			reply(nil, 0, func(r []byte) {
				if hasShm && string(body[4:4+le.Uint16(body)]) == "MIT-SHM" {
					r[8], r[9], r[11] = 1, shmOpcode, 128
				}
			})
		case head[0] == opGetInputFocus:
			reply(nil, 0, nil)
		case head[0] == opGetImage:
			data := make([]byte, 8)
			le.PutUint32(data[0:], shmScreen[0])
			le.PutUint32(data[4:], shmScreen[1])
			reply(data, 24, nil)
		case head[0] == shmOpcode && head[1] == shmQueryVersion:
			reply(nil, 0, func(r []byte) { le.PutUint16(r[8:], 1); le.PutUint16(r[10:], 2) })
		case head[0] == shmOpcode && head[1] == shmAttach:
			if !foreign {
				m, err := unix.SysvShmAttach(int(le.Uint32(body[4:])), 0, 0)
				if err != nil {
					t.Errorf("server attach: %v", err)
				}
				mem = m
			}
		case head[0] == shmOpcode && head[1] == shmGetImage && foreign:
			badSeg(shmGetImage, le.Uint32(body[20:]))
		case head[0] == shmOpcode && head[1] == shmGetImage:
			if mem != nil {
				le.PutUint32(mem[0:], 0xff000000|shmScreen[0])
				le.PutUint32(mem[4:], 0xff000000|shmScreen[1])
			}
			reply(nil, 24, func(r []byte) { le.PutUint32(r[12:], 8) })
		case head[0] == shmOpcode && head[1] == shmDetach && foreign:
			badSeg(shmDetach, le.Uint32(body))
		case head[0] == shmOpcode && head[1] == shmDetach:
		default:
			t.Errorf("unexpected request %d.%d", head[0], head[1])
		}
	}
}

func newShmConn(t *testing.T, hasShm, foreign bool) *Conn {
	cli, srv := net.Pipe()
	t.Cleanup(func() { srv.Close() })
	go fakeShmServer(t, srv, hasShm, foreign)
	x, err := NewConn(cli, "", nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { x.Close() })
	return x
}

func TestShmGetImage(t *testing.T) {
	x := newShmConn(t, true, false)
	s, err := x.NewShmImage(8)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	px := make([]uint32, 2)
	if err := s.GetImage(0, 0, 2, 1, px); err != nil {
		t.Fatal(err)
	}
	if px[0] != shmScreen[0] || px[1] != shmScreen[1] {
		t.Errorf("pixels = %06x %06x", px[0], px[1])
	}
	if err := s.GetImage(0, 0, 3, 1, make([]uint32, 3)); err == nil {
		t.Error("read more than the segment holds")
	}
}

func TestShmRefusedWhereItCannotWork(t *testing.T) {
	if _, err := newShmConn(t, false, false).NewShmImage(8); err == nil {
		t.Error("MIT-SHM used on a server without it")
	}
	// A server in another IPC namespace cannot use the segment; the check
	// must notice, and leave no error behind for the GetImage that follows.
	x := newShmConn(t, true, true)
	if _, err := x.NewShmImage(8); err == nil {
		t.Error("MIT-SHM used with a server that cannot see the segment")
	}
	px := make([]uint32, 2)
	if err := x.GetImage(0, 0, 2, 1, px); err != nil || px[0] != shmScreen[0] {
		t.Errorf("GetImage after the failed check = %06x, %v", px, err)
	}
}
