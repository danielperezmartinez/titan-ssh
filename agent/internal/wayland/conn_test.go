//go:build linux

package wayland

import (
	"net"
	"path/filepath"
	"sync"
	"testing"

	"golang.org/x/sys/unix"
)

// fakeCompositor answers get_registry with two globals and every sync with
// done, and records the other requests (with the descriptors they carry).
type fakeCompositor struct {
	t    *testing.T
	ln   *net.UnixListener
	mu   sync.Mutex
	reqs []fakeRequest
	ids  []uint32 // new ids in the order they arrived
}

type fakeRequest struct {
	object uint32
	opcode uint16
	args   []byte
	fds    []int
}

func newFake(t *testing.T) (*fakeCompositor, string) {
	path := filepath.Join(t.TempDir(), "wayland-test")
	ln, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	f := &fakeCompositor{t: t, ln: ln}
	go f.serve()
	t.Cleanup(func() { ln.Close() })
	return f, path
}

func event(object uint32, opcode uint16, args []byte) []byte {
	b := ne.AppendUint32(nil, object)
	b = ne.AppendUint32(b, uint32(8+len(args))<<16|uint32(opcode))
	return append(b, args...)
}

func str(s string) []byte {
	b := ne.AppendUint32(nil, uint32(len(s)+1))
	b = append(b, s...)
	b = append(b, 0)
	return pad(b)
}

func (f *fakeCompositor) serve() {
	conn, err := f.ln.AcceptUnix()
	if err != nil {
		return
	}
	defer conn.Close()
	var registry uint32
	buf := make([]byte, 0, 4096)
	chunk := make([]byte, 4096)
	oob := make([]byte, 256)
	var fds []int
	for {
		n, oobn, _, _, err := conn.ReadMsgUnix(chunk, oob)
		if err != nil {
			return
		}
		if msgs, _ := unix.ParseSocketControlMessage(oob[:oobn]); len(msgs) > 0 {
			for _, m := range msgs {
				got, _ := unix.ParseUnixRights(&m)
				fds = append(fds, got...)
			}
		}
		buf = append(buf, chunk[:n]...)
		for len(buf) >= 8 {
			size := int(ne.Uint32(buf[4:]) >> 16)
			if len(buf) < size {
				break
			}
			obj, op := ne.Uint32(buf), uint16(ne.Uint32(buf[4:]))
			args := append([]byte(nil), buf[8:size]...)
			buf = buf[size:]
			switch {
			case obj == displayID && op == 1: // get_registry
				registry = ne.Uint32(args)
				f.newID(registry)
				g := append(ne.AppendUint32(nil, 7), str("wl_shm")...)
				_, _ = conn.Write(event(registry, 0, ne.AppendUint32(g, 1)))
				g = append(ne.AppendUint32(nil, 9), str("wl_output")...)
				_, _ = conn.Write(event(registry, 0, ne.AppendUint32(g, 4)))
			case obj == displayID && op == 0: // sync
				cb := ne.Uint32(args)
				f.newID(cb)
				_, _ = conn.Write(event(cb, 0, ne.AppendUint32(nil, 0)))
				_, _ = conn.Write(event(displayID, 1, ne.AppendUint32(nil, cb))) // delete_id
			default:
				r := fakeRequest{object: obj, opcode: op, args: args}
				if obj == 0x7777 { // the test's fd-carrying request
					r.fds, fds = fds, nil
				}
				f.mu.Lock()
				f.reqs = append(f.reqs, r)
				f.mu.Unlock()
				if obj == registry && op == 0 { // bind: the new id is last
					f.newID(ne.Uint32(args[len(args)-4:]))
				}
			}
		}
	}
}

func (f *fakeCompositor) newID(id uint32) {
	f.mu.Lock()
	f.ids = append(f.ids, id)
	f.mu.Unlock()
}

// fixedMinus15 is -1.5 in 24.8 fixed point.
var fixedMinus15 int32 = -384

func TestDialGlobalsAndRequests(t *testing.T) {
	f, path := newFake(t)
	c, err := Dial(path)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	g, ok := c.Find("wl_output")
	if !ok || g.Name != 9 || g.Version != 4 {
		t.Fatalf("wl_output global %+v %v", g, ok)
	}
	if _, ok := c.Find("wl_seat"); ok {
		t.Error("a global the compositor never announced")
	}

	// Many objects created at once from several goroutines: the new ids
	// reach the compositor in the order they were allocated.
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 20; j++ {
				if _, _, err := c.Bind(g, 3, nil); err != nil {
					t.Error(err)
				}
			}
		}()
	}
	wg.Wait()

	// A request with a string, a fixed number and a descriptor.
	mem, err := NewMemfd("test", 64)
	if err != nil {
		t.Fatal(err)
	}
	defer mem.Close()
	if err := c.Request(0x7777, 3, "héllo", Fixed(-1.5), Fd(mem.Fd), int32(-2)); err != nil {
		t.Fatal(err)
	}
	if err := c.Roundtrip(); err != nil {
		t.Fatal(err)
	}

	f.mu.Lock()
	defer f.mu.Unlock()
	// A fresh id (not one freed and reused) must be the next one.
	next := uint32(2)
	for _, id := range f.ids {
		if id < next { // handed out before and freed by delete_id since
			continue
		}
		if id != next {
			t.Fatalf("new id %d where %d was next: out of order", id, next)
		}
		next++
	}
	if next < 2+160 {
		t.Fatalf("only %d fresh ids", next-2)
	}
	last := f.reqs[len(f.reqs)-1]
	if last.object != 0x7777 || last.opcode != 3 {
		t.Fatalf("last request %+v", last)
	}
	want := append(str("héllo"), ne.AppendUint32(nil, uint32(fixedMinus15))...)
	want = ne.AppendUint32(want, uint32(0xfffffffe))
	if string(last.args) != string(want) {
		t.Errorf("arguments % x, want % x", last.args, want)
	}
	if len(last.fds) != 1 {
		t.Fatalf("%d descriptors passed, want 1", len(last.fds))
	}
	unix.Close(last.fds[0])
}

func TestProtocolErrorEndsTheConnection(t *testing.T) {
	path := filepath.Join(t.TempDir(), "wayland-err")
	ln, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		conn, err := ln.AcceptUnix()
		if err != nil {
			return
		}
		defer conn.Close()
		b := make([]byte, 64)
		_, _ = conn.Read(b)
		args := ne.AppendUint32(ne.AppendUint32(nil, 1), 2)
		_, _ = conn.Write(event(displayID, 0, append(args, str("bad request")...)))
		_, _ = conn.Read(b)
	}()
	_, err = Dial(path)
	pe, ok := err.(*ProtocolError)
	if !ok || pe.Code != 2 || pe.Message != "bad request" {
		t.Fatalf("error %v", err)
	}
}

func TestSocketPath(t *testing.T) {
	for _, c := range [][3]string{
		{"/run/user/1000", "wayland-1", "/run/user/1000/wayland-1"},
		{"/run/user/1000", "", "/run/user/1000/wayland-0"},
		{"/run/user/1000", "/tmp/other", "/tmp/other"},
	} {
		if got := SocketPath(c[0], c[1]); got != c[2] {
			t.Errorf("SocketPath(%q, %q) = %q, want %q", c[0], c[1], got, c[2])
		}
	}
}
