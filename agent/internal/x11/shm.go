package x11

import (
	"encoding/binary"
	"errors"
)

// MIT-SHM request minor opcodes.
const (
	shmQueryVersion = 0
	shmAttach       = 1
	shmDetach       = 2
	shmGetImage     = 4
)

// shmBand bounds the bytes one ShmGetImage asks for. The server serves one
// request at a time: in one request for a whole 2560×1440 screen, the
// pointer connection's requests waited up to 20 ms, against under a
// millisecond with bands of 512 KB, which cost about 10% more per capture.
const shmBand = 512 << 10

// ShmImage reads the root window through MIT-SHM: the server writes the
// pixels into a shared memory segment instead of sending them over the
// socket, so a capture costs the server a memory copy and the connection a
// few small round trips. It only works with a server on this machine and in
// the same IPC namespace; NewShmImage checks that and fails otherwise, so the
// caller can fall back to GetImage.
type ShmImage struct {
	x    *Conn
	op   uint8
	seg  uint32 // the segment's ID on the server
	mem  []byte // the segment, mapped here
	size int
}

// NewShmImage creates a shared segment of size bytes, attaches it to the
// server and checks, with a GetImage of the same pixels, that the server
// writes into this very segment. A segment ID is only meaningful in one IPC
// namespace: a server in another one could attach a different segment with
// the same ID.
func (x *Conn) NewShmImage(size int) (*ShmImage, error) {
	if err := x.checkPixelFormat(); err != nil {
		return nil, err
	}
	op, err := x.extension("MIT-SHM")
	if err != nil {
		return nil, err
	}
	if _, err := x.roundTrip(request(4, op, shmQueryVersion)); err != nil {
		return nil, err
	}
	s := &ShmImage{x: x, op: op, size: size}
	if err := s.attach(); err != nil {
		return nil, err
	}
	if err := s.verify(); err != nil {
		// The server may not know the segment: the error ShmDetach then
		// causes must not reach the GetImage that replaces it.
		s.Close()
		_ = x.Sync()
		return nil, err
	}
	return s, nil
}

// Size is the segment's size in bytes.
func (s *ShmImage) Size() int { return s.size }

// GetImage reads the w×h rectangle at (x0, y0) of the root window into dst
// like Conn.GetImage does (w*h*4 must fit in the segment), in bands.
func (s *ShmImage) GetImage(x0, y0, w, h int, dst []uint32) error {
	if w <= 0 || h <= 0 {
		return nil
	}
	if w*h*4 > s.size || len(dst) < w*h {
		return errors.New("x11: ShmGetImage area too large")
	}
	band := max(1, shmBand/(w*4))
	for y := 0; y < h; y += band {
		bh := min(band, h-y)
		req := request(32, s.op, shmGetImage)
		le.PutUint32(req[4:], s.x.Setup.Root)
		le.PutUint16(req[8:], uint16(x0))
		le.PutUint16(req[10:], uint16(y0+y))
		le.PutUint16(req[12:], uint16(w))
		le.PutUint16(req[14:], uint16(bh))
		le.PutUint32(req[16:], 0xffffffff)
		req[20] = 2 // ZPixmap
		le.PutUint32(req[24:], s.seg)
		le.PutUint32(req[28:], uint32(y*w*4))
		if _, err := s.x.roundTrip(req); err != nil {
			return err
		}
	}
	s.x.toRGB(dst[:w*h], s.mem)
	return nil
}

// verify reads one row of the screen both ways, over a segment that holds
// its complement, and fails unless they agree.
func (s *ShmImage) verify() error {
	w := min(int(s.x.Setup.Width), s.size/4, 256)
	if w <= 0 {
		return errors.New("x11: no screen to check MIT-SHM with")
	}
	want := make([]uint32, w)
	if err := s.x.GetImage(0, 0, w, 1, want); err != nil {
		return err
	}
	for i, p := range want {
		if s.x.Setup.ImageByteOrder == 0 {
			le.PutUint32(s.mem[i*4:], ^p)
		} else {
			binary.BigEndian.PutUint32(s.mem[i*4:], ^p)
		}
	}
	got := make([]uint32, w)
	if err := s.GetImage(0, 0, w, 1, got); err != nil {
		return err
	}
	for i := range got {
		if got[i] != want[i] {
			return errors.New("x11: the server does not write into our MIT-SHM segment")
		}
	}
	return nil
}

// Close detaches the segment from the server and from this process.
func (s *ShmImage) Close() error {
	req := request(8, s.op, shmDetach)
	le.PutUint32(req[4:], s.seg)
	err := s.x.sendOnly(req)
	if derr := s.detach(); err == nil {
		err = derr
	}
	return err
}
