//go:build linux

package pipewire

import (
	"encoding/binary"
	"reflect"
	"sync/atomic"
	"testing"

	"golang.org/x/sys/unix"
)

func TestPodRoundTrip(t *testing.T) {
	p := pStruct(pInt(-7), pString("hola"), pLong(1<<40), pBool(true), pRect(1920, 1080), pNone(), enumFormat())
	v, rest, err := parsePod(p)
	if err != nil || len(rest) != 0 {
		t.Fatalf("parse: %v rest %d", err, len(rest))
	}
	f, err := v.fields()
	if err != nil || len(f) != 7 {
		t.Fatalf("fields: %d %v", len(f), err)
	}
	if n, _ := f[0].int(); n != -7 {
		t.Errorf("int = %d", n)
	}
	if s, _ := f[1].string(); s != "hola" {
		t.Errorf("string = %q", s)
	}
	if n, _ := f[2].long(); n != 1<<40 {
		t.Errorf("long = %d", n)
	}
	if w, h, _ := f[4].pair(); w != 1920 || h != 1080 {
		t.Errorf("rect = %dx%d", w, h)
	}
	if s, err := f[5].string(); s != "" || err != nil {
		t.Errorf("none as string = %q %v", s, err)
	}
	o, err := f[6].object()
	if err != nil || o.typ != objectFormat || o.id != paramEnumFormat {
		t.Fatalf("object: %+v %v", o, err)
	}
	if v, _ := o.props[formatVideoFormat].fixed().uint(); v != FormatBGRx {
		t.Errorf("default format = %d", v)
	}
	if w, h, _ := o.props[formatVideoSize].pair(); w != 1920 || h != 1080 {
		t.Errorf("default size = %dx%d", w, h)
	}
	// Every POD is padded to 8 bytes.
	if len(pString("x"))%8 != 0 || len(pInt(1))%8 != 0 {
		t.Error("unpadded POD")
	}
	// Truncations fail cleanly.
	for i := 0; i < len(p); i++ {
		v, _, err := parsePod(p[:i])
		if err == nil {
			_, _ = v.fields()
		}
	}
}

func format(fmt_ uint32, w, h uint32, modifier bool) value {
	props := []prop{
		{key: formatMediaType, value: pID(mediaVideo)},
		{key: formatMediaSubtype, value: pID(subtypeRaw)},
		{key: formatVideoFormat, value: pID(fmt_)},
		{key: formatVideoSize, value: pRect(w, h)},
	}
	if modifier {
		props = append(props, prop{key: formatVideoModif, value: pLong(0)})
	}
	v, _, _ := parsePod(pObject(objectFormat, paramFormat, props...))
	return v
}

func TestParseFormat(t *testing.T) {
	f, err := parseFormat(format(FormatRGBx, 2560, 1440, false))
	if err != nil || f != (Format{Format: FormatRGBx, Width: 2560, Height: 1440}) {
		t.Fatalf("format = %+v %v", f, err)
	}
	if _, err := parseFormat(format(FormatBGRx, 800, 600, true)); err == nil {
		t.Error("DMA-BUF format accepted")
	}
	if _, err := parseFormat(format(99, 800, 600, false)); err == nil {
		t.Error("unknown pixel format accepted")
	}
	if _, err := parseFormat(format(FormatBGRx, 0, 600, false)); err == nil {
		t.Error("empty size accepted")
	}
}

func TestToARGB(t *testing.T) {
	// Bytes in memory for red (R=0x11 G=0x22 B=0x33, A=0x80) in each format.
	cases := map[uint32][4]byte{
		FormatBGRA: {0x33, 0x22, 0x11, 0x80},
		FormatRGBA: {0x11, 0x22, 0x33, 0x80},
		FormatARGB: {0x80, 0x11, 0x22, 0x33},
		FormatABGR: {0x80, 0x33, 0x22, 0x11},
	}
	for f, b := range cases {
		if got := toARGB(f, binary.LittleEndian.Uint32(b[:])); got != 0x80112233 {
			t.Errorf("format %d: %#x", f, got)
		}
	}
	opaque := map[uint32][4]byte{
		FormatBGRx: {0x33, 0x22, 0x11, 0},
		FormatRGBx: {0x11, 0x22, 0x33, 0},
		FormatXRGB: {0, 0x11, 0x22, 0x33},
		FormatXBGR: {0, 0x33, 0x22, 0x11},
	}
	for f, b := range opaque {
		if got := toARGB(f, binary.LittleEndian.Uint32(b[:])); got != 0xff112233 {
			t.Errorf("format %d: %#x", f, got)
		}
	}
}

// testBuffer builds a buffer like the producer's: header, crop, damage and
// cursor metadata, then one chunk and its data.
type testBuffer struct {
	b      *buffer
	header []byte
	crop   []byte
	damage []byte
	cursor []byte
	chunk  []byte
	data   []byte
}

func newTestBuffer(w, h, stride int) *testBuffer {
	tb := &testBuffer{
		header: make([]byte, 32), crop: make([]byte, 16), damage: make([]byte, 16*4),
		cursor: make([]byte, 28+20+4*4*4), chunk: make([]byte, 16), data: make([]byte, stride*h),
	}
	tb.b = &buffer{
		metas: []bufMeta{{metaHeader, tb.header}, {metaVideoCrop, tb.crop}, {metaDamage, tb.damage}, {metaCursor, tb.cursor}},
		datas: []bufData{{typ: dataMemFd, data: tb.data, chunk: tb.chunk}},
	}
	le.PutUint32(tb.chunk[4:], uint32(len(tb.data)))
	le.PutUint32(tb.chunk[8:], uint32(stride))
	return tb
}

func (tb *testBuffer) fill(stride int, px func(x, y int) uint32, w, h int) {
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			le.PutUint32(tb.data[y*stride+x*4:], px(x, y))
		}
	}
}

func (tb *testBuffer) setDamage(rects ...Rect) {
	clear(tb.damage)
	for i, r := range rects {
		o := i * 16
		le.PutUint32(tb.damage[o:], uint32(r.X))
		le.PutUint32(tb.damage[o+4:], uint32(r.Y))
		le.PutUint32(tb.damage[o+8:], uint32(r.W))
		le.PutUint32(tb.damage[o+12:], uint32(r.H))
	}
}

func TestConsumeCropDamageAndCursor(t *testing.T) {
	const w, h, stride = 8, 6, 40 // stride wider than a row
	s := &Stream{format: Format{FormatBGRx, w, h}, formatSet: true, newFrame: make(chan struct{}, 1)}
	tb := newTestBuffer(w, h, stride)
	tb.fill(stride, func(x, y int) uint32 { return uint32(y<<8 | x) }, w, h)

	// No damage meta on the first frame: whole copy, unknown damage.
	tb.setDamage()
	s.consume(tb.b)
	dst := make([]uint32, w*h)
	if !s.Read(dst, w, h) || dst[5*w+7] != 0x0507 {
		t.Fatalf("first frame: %#x", dst[5*w+7])
	}
	if _, ok := s.Damage(); ok {
		t.Error("damage known after the first read")
	}

	// Two frames each damaging a rectangle: only those are copied, and both
	// are what changed since the last Read.
	tb.fill(stride, func(x, y int) uint32 { return 0xffffff }, w, h)
	tb.setDamage(Rect{1, 1, 2, 2})
	s.consume(tb.b)
	tb.setDamage(Rect{6, 4, 5, 5}) // overflows the frame: clipped
	s.consume(tb.b)
	s.Read(dst, w, h)
	if dst[1*w+1] != 0xffffff || dst[0] != 0 || dst[5*w+7] != 0xffffff || dst[3*w+3] != 0x0303 {
		t.Fatalf("damaged copy wrong: %#x %#x %#x %#x", dst[1*w+1], dst[0], dst[5*w+7], dst[3*w+3])
	}
	rects, ok := s.Damage()
	if !ok || !reflect.DeepEqual(rects, []Rect{{1, 1, 2, 2}, {6, 4, 2, 2}}) {
		t.Fatalf("damage = %v %v", rects, ok)
	}

	// A corrupted chunk is dropped; a cursor-only buffer (size 0) still moves
	// the cursor.
	le.PutUint32(tb.chunk[12:], chunkCorrupted)
	tb.fill(stride, func(x, y int) uint32 { return 0x123456 }, w, h)
	s.consume(tb.b)
	le.PutUint32(tb.chunk[12:], 0)
	le.PutUint32(tb.chunk[4:], 0)
	c := tb.cursor
	le.PutUint32(c, 1)
	le.PutUint32(c[8:], 3)
	le.PutUint32(c[12:], 4)
	le.PutUint32(c[16:], 1)
	le.PutUint32(c[20:], 2)
	le.PutUint32(c[24:], 28)
	bm := c[28:]
	le.PutUint32(bm, FormatRGBA)
	le.PutUint32(bm[4:], 4)
	le.PutUint32(bm[8:], 4)
	le.PutUint32(bm[12:], 16)
	le.PutUint32(bm[16:], 20)
	copy(bm[20:], []byte{0x11, 0x22, 0x33, 0x80})
	s.consume(tb.b)
	s.Read(dst, w, h)
	if dst[0] == 0x123456 {
		t.Error("corrupted frame was copied")
	}
	cur := s.Cursor()
	if !cur.Visible || cur.X != 3 || cur.Y != 4 || cur.HotX != 1 || cur.HotY != 2 || cur.Width != 4 || cur.Pixels[0] != 0x80112233 || cur.Serial == 0 {
		t.Fatalf("cursor = %+v", cur)
	}
	serial := cur.Serial
	le.PutUint32(bm[4:], 0) // unchanged image
	s.consume(tb.b)
	if s.Cursor().Serial != serial {
		t.Error("serial moved without a new image")
	}
	le.PutUint32(c, 0)
	s.consume(tb.b)
	if s.Cursor().Visible {
		t.Error("cursor id 0 still visible")
	}

	// A crop selects part of the frame, which takes the crop's size.
	le.PutUint32(tb.chunk[4:], uint32(len(tb.data)))
	tb.setDamage()
	tb.fill(stride, func(x, y int) uint32 { return uint32(y<<8 | x) }, w, h)
	le.PutUint32(tb.crop, 2)
	le.PutUint32(tb.crop[4:], 1)
	le.PutUint32(tb.crop[8:], 4)
	le.PutUint32(tb.crop[12:], 3)
	s.consume(tb.b)
	if cw, ch := s.Size(); cw != 4 || ch != 3 {
		t.Fatalf("cropped size %dx%d", cw, ch)
	}
	small := make([]uint32, 12)
	if !s.Read(small, 4, 3) || small[0] != 0x0102 || small[11] != 0x0305 {
		t.Fatalf("crop = %#x %#x", small[0], small[11])
	}
	if s.Read(dst, w, h) {
		t.Error("read with the old size succeeded")
	}
}

func eventfd(t *testing.T) int {
	fd, err := unix.Eventfd(0, unix.EFD_NONBLOCK|unix.EFD_CLOEXEC)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { unix.Close(fd) })
	return fd
}

func readEventfd(fd int) uint64 {
	var b [8]byte
	if _, err := unix.Read(fd, b[:]); err != nil {
		return 0
	}
	return binary.NativeEndian.Uint64(b[:])
}

func TestCycle(t *testing.T) {
	const w, h = 4, 2
	for _, tc := range []struct {
		name          string
		async         bool // the activation flag set by the server
		serverVersion uint32
		asyncIO       bool
		wantTrigger   bool
	}{
		{"0.3 server", false, 0, false, true},
		{"1.x sync", false, 1, false, true},
		{"1.x async", true, 1, true, false},
		{"1.0 async flag ignored", true, 0, false, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s := &Stream{format: Format{FormatBGRx, w, h}, formatSet: true, newFrame: make(chan struct{}, 1),
				mixes: map[uint32]*mix{}, peers: map[uint32]*peer{}}
			s.activation = make([]byte, actMinSize)
			if tc.async {
				le.PutUint32(s.activation[actFlags:], actFlagAsync)
			}
			le.PutUint32(s.activation[actServerVersion:], tc.serverVersion)
			tb := newTestBuffer(w, h, w*4)
			tb.fill(w*4, func(x, y int) uint32 { return 0xabcdef }, w, h)
			io := make([]byte, 16)
			slot := io
			if tc.asyncIO {
				s.position = make([]byte, positionCycle+4)
				le.PutUint32(s.position[positionCycle:], 3) // odd cycle: second slot
				slot = io[8:]
			}
			le.PutUint32(slot, statusHaveData)
			le.PutUint32(slot[4:], 0)
			s.mixes[0] = &mix{io: io, async: tc.asyncIO, buffers: []*buffer{tb.b}}

			peerAct := make([]byte, actMinSize)
			le.PutUint32(peerAct[actServerVersion:], tc.serverVersion)
			le.PutUint32(peerAct[actStatePending:], 1)
			pfd := eventfd(t)
			s.peers[7] = &peer{act: peerAct, fd: pfd}

			// Not triggered yet: nothing happens.
			s.cycle()
			if s.frames != 0 {
				t.Fatal("processed an untriggered cycle")
			}
			atomic.StoreUint32(u32at(s.activation, actStatus), actTriggered)
			s.cycle()
			if s.frames != 1 || le.Uint32(slot) != statusNeedData {
				t.Fatalf("frames %d, io status %d", s.frames, le.Uint32(slot))
			}
			if st := le.Uint32(s.activation[actStatus:]); st != actFinished {
				t.Errorf("status %d", st)
			}
			if st := int32(le.Uint32(s.activation[actState0Status:])); st != statusNeedData|statusHaveData {
				t.Errorf("state status %d", st)
			}
			triggered := readEventfd(pfd) == 1
			if triggered != tc.wantTrigger {
				t.Fatalf("peer triggered = %v, want %v", triggered, tc.wantTrigger)
			}
			if tc.wantTrigger && le.Uint32(peerAct[actStatus:]) != actTriggered {
				t.Errorf("peer status %d", le.Uint32(peerAct[actStatus:]))
			}
		})
	}
}
