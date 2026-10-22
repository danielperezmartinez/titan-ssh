//go:build linux

package pipewire

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"strconv"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"

	"golang.org/x/sys/unix"
)

// client-node methods and events (pipewire/extensions/client-node.h).
const (
	nodeUpdate     = 2
	nodePortUpdate = 3
	nodeSetActive  = 4

	evNodeTransport      = 0
	evNodeSetIO          = 2
	evNodeCommand        = 4
	evNodePortSetParam   = 7
	evNodePortUseBuffers = 8
	evNodePortSetIO      = 9
	evNodeSetActivation  = 10
	evNodePortSetMixInfo = 11
)

// clientNodeVersion is the client-node interface version asked for: 4, the
// one before the client took over its own activation status (PipeWire 1.x
// keeps doing that on the server for clients below 6), so one code path
// serves PipeWire 0.3 and 1.x.
const clientNodeVersion = 4

// SPA ids (spa/param, spa/node, spa/buffer).
const (
	paramEnumFormat = 3
	paramFormat     = 4

	paramInfoRead  = 1 << 1
	paramInfoWrite = 1 << 2

	objectFormat = 0x40003

	formatMediaType    = 1
	formatMediaSubtype = 2
	formatVideoFormat  = 0x20001
	formatVideoModif   = 0x20002
	formatVideoSize    = 0x20003
	formatVideoRate    = 0x20004

	mediaVideo = 2
	subtypeRaw = 1

	ioBuffers      = 1
	ioPosition     = 7
	ioAsyncBuffers = 10

	statusNeedData = 1
	statusHaveData = 2

	metaHeader    = 1
	metaVideoCrop = 2
	metaDamage    = 3
	metaCursor    = 5

	chunkCorrupted  = 1
	headerCorrupted = 2

	nodeChangeFlags  = 1
	portChangeFlags  = 1
	portChangeRate   = 2
	portChangeProps  = 4
	portChangeParams = 8

	updateParams = 1
	updateInfo   = 2

	idInvalid = 0xffffffff
)

// Video formats (spa/param/video/raw.h) this client reads.
const (
	FormatRGBx = 7
	FormatBGRx = 8
	FormatXRGB = 9
	FormatXBGR = 10
	FormatRGBA = 11
	FormatBGRA = 12
	FormatARGB = 13
	FormatABGR = 14
)

// pw_node_activation, the fields used: they sit at the same offsets in
// PipeWire 0.3 and 1.x.
const (
	actStatus        = 0
	actStatePending  = 16 // state[0].pending
	actState0Status  = 8
	actServerVersion = 544 // 0 before PipeWire 1.2
	actFlags         = 556 // PW_NODE_ACTIVATION_FLAG_*
	actMinSize       = 560

	actNotTriggered = 0
	actTriggered    = 1
	actAwake        = 2
	actFinished     = 3

	actFlagAsync = 1 << 1

	positionCycle = 148 // spa_io_position.clock.cycle
)

// Stream consumes one video stream (a PipeWire node) through a client-node
// with one input port.
type Stream struct {
	conn   *Conn
	id     uint32
	target uint32

	// rtMu guards what a processing cycle uses: the activation, the io
	// areas and the buffers.
	rtMu       sync.Mutex
	readfd     int
	writefd    int
	activation []byte
	position   []byte
	mixes      map[uint32]*mix
	peers      map[uint32]*peer
	format     Format
	formatSet  bool
	rtStarted  bool
	cycles     uint64

	frameMu sync.Mutex
	frame   []byte // the last frame, rows of width*4 bytes
	frameW  int
	frameH  int
	frameF  uint32
	frames  uint64 // counts frames received
	cursor  Cursor
	// damage gathers what the frames since the last Read changed (damageK:
	// every one of them said); Read moves it to readDamage, what that Read
	// changed against the one before.
	damage      []Rect
	damageK     bool
	readDamage  []Rect
	readDamageK bool
	readOnce    bool
	newFrame    chan struct{}

	endOnce sync.Once
	err     error
	done    chan struct{}
}

// Format is the negotiated video format.
type Format struct {
	Format        uint32
	Width, Height int
}

// Cursor is the pointer as the stream's cursor metadata gives it, in the
// stream's pixels.
type Cursor struct {
	Visible bool
	X, Y    int
	HotX    int
	HotY    int
	// Serial changes whenever the image changes.
	Serial        uint32
	Width, Height int
	// Pixels is the image, ARGB with straight alpha.
	Pixels []uint32
}

// Rect is a damaged area of a frame.
type Rect struct{ X, Y, W, H int }

type mix struct {
	io      []byte
	async   bool
	buffers []*buffer
}

type peer struct {
	act []byte
	fd  int
}

type buffer struct {
	metas []bufMeta
	datas []bufData
}

type bufMeta struct {
	typ  uint32
	data []byte
}

type bufData struct {
	typ   uint32
	data  []byte
	chunk []byte
}

// Consume connects a new stream to the node target (the node id from the
// portal) on c. name is the stream's node name.
func (c *Conn) Consume(ctx context.Context, target uint32, name string) (*Stream, error) {
	s := &Stream{
		conn: c, id: c.newID(), target: target, readfd: -1, writefd: -1,
		mixes: map[uint32]*mix{}, peers: map[uint32]*peer{},
		newFrame: make(chan struct{}, 1), done: make(chan struct{}),
	}
	c.mu.Lock()
	if c.err != nil {
		c.mu.Unlock()
		return nil, c.err
	}
	c.streams[s.id] = s
	c.mu.Unlock()

	t := strconv.FormatUint(uint64(target), 10)
	props := [][2]string{
		{"media.type", "Video"},
		{"media.category", "Capture"},
		{"media.role", "Screen"},
		{"media.class", "Stream/Input/Video"},
		{"node.name", name},
		{"node.description", name},
		// The id the portal gives; target.object would take it for a serial.
		{"node.target", t},
		{"node.autoconnect", "true"},
		{"node.dont-reconnect", "true"},
		{"node.async", "true"},
		{"node.loop.class", "main"},
	}
	if err := c.send(idCore, coreCreateObject, pStruct(
		pString("client-node"), pString("PipeWire:Interface:ClientNode"), pInt(clientNodeVersion),
		pDict(props), pInt(int32(s.id)),
	), nil); err != nil {
		return nil, err
	}
	// The node: one input port, nothing else. Only the flags change: a
	// props change with no props crashes PipeWire 1.4 (a NULL dict).
	if err := c.send(s.id, nodeUpdate, pStruct(
		pInt(updateParams|updateInfo), pInt(0),
		pStruct(pInt(1), pInt(0), pLong(nodeChangeFlags), pLong(0), pInt(0), pInt(0)),
	), nil); err != nil {
		return nil, err
	}
	if err := s.portUpdate(nil); err != nil {
		return nil, err
	}
	if err := c.send(s.id, nodeSetActive, pStruct(pBool(true)), nil); err != nil {
		return nil, err
	}
	if err := c.Sync(ctx); err != nil {
		return nil, err
	}
	select {
	case <-s.done:
		return nil, s.err
	default:
	}
	return s, nil
}

// enumFormat is what the port accepts: raw video, 32-bit RGB in any order,
// any size and rate, and no modifier, so the producer uses memfd/SHM
// buffers rather than DMA-BUF.
func enumFormat() pod {
	return pObject(objectFormat, paramEnumFormat,
		prop{key: formatMediaType, value: pID(mediaVideo)},
		prop{key: formatMediaSubtype, value: pID(subtypeRaw)},
		prop{key: formatVideoFormat, value: pChoice(choiceEnum, podID, 4, u32s(FormatBGRx, FormatBGRx, FormatBGRA, FormatRGBx, FormatRGBA, FormatXRGB, FormatXBGR, FormatARGB, FormatABGR)...)},
		prop{key: formatVideoSize, value: pChoice(choiceRange, podRectangle, 8, pairs([2]uint32{1920, 1080}, [2]uint32{1, 1}, [2]uint32{16384, 16384})...)},
		prop{key: formatVideoRate, value: pChoice(choiceRange, podFraction, 8, pairs([2]uint32{30, 1}, [2]uint32{0, 1}, [2]uint32{1000, 1})...)},
	)
}

// portUpdate tells the server the port's params: EnumFormat, and the Format
// once set.
func (s *Stream) portUpdate(format pod) error {
	params := []pod{enumFormat()}
	infos := []pod{pID(paramEnumFormat), pInt(paramInfoRead), pID(paramFormat), pInt(paramInfoWrite)}
	if format != nil {
		params = append(params, format)
		infos[3] = pInt(paramInfoRead | paramInfoWrite)
	}
	fields := []pod{pInt(0), pInt(0), pInt(updateParams | updateInfo), pInt(int32(len(params)))}
	fields = append(fields, params...)
	info := []pod{
		pLong(portChangeFlags | portChangeRate | portChangeProps | portChangeParams), pLong(0),
		pInt(0), pInt(1),
		pInt(1), pString("port.name"), pString("input_0"),
		pInt(int32(len(infos) / 2)),
	}
	info = append(info, infos...)
	fields = append(fields, pStruct(info...))
	return s.conn.send(s.id, nodePortUpdate, pStruct(fields...), nil)
}

// Done is closed when the stream ends; Err then says why.
func (s *Stream) Done() <-chan struct{} { return s.done }

// Err is why the stream ended.
func (s *Stream) Err() error {
	select {
	case <-s.done:
		return s.err
	default:
		return nil
	}
}

func (s *Stream) fail(err error) { s.end(err) }

// end stops the stream for good.
func (s *Stream) end(err error) {
	s.endOnce.Do(func() {
		s.err = err
		close(s.done)
	})
}

// Close ends the stream on the server.
func (s *Stream) Close() {
	s.end(ErrClosed)
	s.conn.mu.Lock()
	delete(s.conn.streams, s.id)
	s.conn.mu.Unlock()
	_ = s.conn.send(idCore, 7 /* destroy */, pStruct(pInt(int32(s.id))), nil)
	s.rtMu.Lock()
	if !s.rtStarted {
		s.closeRT()
	}
	s.rtMu.Unlock()
}

// closeRT releases the descriptors of the processing side. Called under rtMu.
func (s *Stream) closeRT() {
	for _, fd := range []int{s.readfd, s.writefd} {
		if fd >= 0 {
			_ = unix.Close(fd)
		}
	}
	s.readfd, s.writefd = -1, -1
	for _, p := range s.peers {
		_ = unix.Close(p.fd)
	}
	s.peers = map[uint32]*peer{}
}

// event handles a client-node event. Errors in one end the stream.
func (s *Stream) event(m *message) {
	if err := s.handle(m); err != nil {
		s.fail(err)
	}
}

// Debugf, when set, logs the client-node's events and cycles (the agent
// sets it from TITAN_AGENT_DEBUG).
var Debugf func(format string, args ...any)

func debugf(format string, args ...any) {
	if Debugf != nil {
		Debugf(format, args...)
	}
}

func (s *Stream) handle(m *message) error {
	debugf("pipewire: node %d event %d (%d bytes, %d fds)", s.id, m.opcode, len(m.body), len(m.fds))
	switch m.opcode {
	case evNodeTransport:
		f, err := structArgs(m.body, 5)
		if err != nil {
			return err
		}
		rfd, err := m.fd(f[0])
		if err != nil {
			return err
		}
		wfd, err := m.fd(f[1])
		if err != nil {
			_ = unix.Close(rfd)
			return err
		}
		act, err := s.memArg(f[2], f[3], f[4])
		if err != nil {
			_ = unix.Close(rfd)
			_ = unix.Close(wfd)
			return err
		}
		if len(act) < actMinSize {
			return errors.New("pipewire: activation area too small")
		}
		s.rtMu.Lock()
		if s.readfd >= 0 {
			_ = unix.Close(s.readfd)
		}
		if s.writefd >= 0 {
			_ = unix.Close(s.writefd)
		}
		s.readfd, s.writefd, s.activation = rfd, wfd, act
		start := !s.rtStarted
		s.rtStarted = true
		s.rtMu.Unlock()
		if start {
			go s.run()
		}
	case evNodeSetIO:
		f, err := structArgs(m.body, 4)
		if err != nil {
			return err
		}
		id, _ := f[0].uint()
		if id != ioPosition {
			return nil
		}
		area, err := s.memArg(f[1], f[2], f[3])
		if err != nil {
			return err
		}
		s.rtMu.Lock()
		s.position = area
		s.rtMu.Unlock()
	case evNodeCommand:
		// Start, Pause and Suspend need nothing here: the graph only
		// signals a running node.
	case evNodePortSetParam:
		f, err := structArgs(m.body, 5)
		if err != nil {
			return err
		}
		id, _ := f[2].uint()
		if id != paramFormat {
			return nil
		}
		s.rtMu.Lock()
		for _, mx := range s.mixes {
			mx.buffers = nil
		}
		s.rtMu.Unlock()
		if f[4].typ != podObject {
			s.rtMu.Lock()
			s.formatSet = false
			s.rtMu.Unlock()
			return s.portUpdate(nil)
		}
		format, err := parseFormat(f[4])
		if err != nil {
			return err
		}
		if Debugf != nil {
			if o, err := f[4].object(); err == nil {
				for k, v := range o.props {
					debugf("pipewire: format prop %#x type %d body %x", k, v.typ, v.body)
				}
			}
		}
		s.rtMu.Lock()
		s.format, s.formatSet = format, true
		s.rtMu.Unlock()
		raw := header(uint32(len(f[4].body)), podObject)
		raw = pad8(append(raw, f[4].body...))
		return s.portUpdate(raw)
	case evNodePortUseBuffers:
		return s.useBuffers(m)
	case evNodePortSetIO:
		f, err := structArgs(m.body, 7)
		if err != nil {
			return err
		}
		mixID, _ := f[2].uint()
		id, _ := f[3].uint()
		if id != ioBuffers && id != ioAsyncBuffers {
			return nil
		}
		area, err := s.memArg(f[4], f[5], f[6])
		if err != nil {
			return err
		}
		s.rtMu.Lock()
		mx := s.mixFor(mixID)
		mx.io, mx.async = area, id == ioAsyncBuffers && len(area) >= 16
		s.rtMu.Unlock()
	case evNodeSetActivation:
		f, err := structArgs(m.body, 5)
		if err != nil {
			return err
		}
		nodeID, _ := f[0].uint()
		memid, _ := f[2].uint()
		s.rtMu.Lock()
		defer s.rtMu.Unlock()
		if old, ok := s.peers[nodeID]; ok {
			_ = unix.Close(old.fd)
			delete(s.peers, nodeID)
		}
		if memid == idInvalid {
			return nil
		}
		fd, err := m.fd(f[1])
		if err != nil {
			return err
		}
		act, err := s.memArg(f[2], f[3], f[4])
		if err != nil || len(act) < actMinSize {
			_ = unix.Close(fd)
			if err == nil {
				err = errors.New("pipewire: peer activation too small")
			}
			return err
		}
		s.peers[nodeID] = &peer{act: act, fd: fd}
	case evNodePortSetMixInfo:
		f, err := structArgs(m.body, 4)
		if err != nil {
			return err
		}
		mixID, _ := f[2].uint()
		peerID, _ := f[3].uint()
		if peerID == idInvalid {
			s.rtMu.Lock()
			delete(s.mixes, mixID)
			s.rtMu.Unlock()
		}
	}
	return nil
}

// mixFor returns the port mix with this id, creating it. Called under rtMu.
func (s *Stream) mixFor(id uint32) *mix {
	mx, ok := s.mixes[id]
	if !ok {
		mx = &mix{}
		s.mixes[id] = mx
	}
	return mx
}

// memArg maps the (memid, offset, size) arguments of an event; memid
// SPA_ID_INVALID is no memory.
func (s *Stream) memArg(memv, offv, sizev value) ([]byte, error) {
	memid, _ := memv.uint()
	if memid == idInvalid {
		return nil, nil
	}
	off, _ := offv.uint()
	size, _ := sizev.uint()
	mb, err := s.conn.mem(memid)
	if err != nil {
		return nil, err
	}
	return mb.bytes(off, size)
}

func parseFormat(v value) (Format, error) {
	o, err := v.object()
	if err != nil {
		return Format{}, err
	}
	var f Format
	if p, ok := o.props[formatMediaType]; ok {
		if t, _ := p.fixed().uint(); t != mediaVideo {
			return f, errors.New("pipewire: the stream is not video")
		}
	}
	if _, ok := o.props[formatVideoModif]; ok {
		return f, errors.New("pipewire: the producer chose DMA-BUF buffers, which this client cannot read")
	}
	p, ok := o.props[formatVideoFormat]
	if !ok {
		return f, errors.New("pipewire: the format has no video format")
	}
	f.Format, _ = p.fixed().uint()
	switch f.Format {
	case FormatRGBx, FormatBGRx, FormatXRGB, FormatXBGR, FormatRGBA, FormatBGRA, FormatARGB, FormatABGR:
	default:
		return f, fmt.Errorf("pipewire: unsupported video format %d", f.Format)
	}
	if p, ok := o.props[formatVideoSize]; ok {
		w, h, err := p.pair()
		if err != nil {
			return f, err
		}
		f.Width, f.Height = int(w), int(h)
	}
	if f.Width <= 0 || f.Height <= 0 || f.Width > 16384 || f.Height > 16384 {
		return f, fmt.Errorf("pipewire: bad video size %dx%d", f.Width, f.Height)
	}
	return f, nil
}

// useBuffers maps the buffers the producer allocated for the port.
func (s *Stream) useBuffers(m *message) error {
	f, err := structArgs(m.body, 5)
	if err != nil {
		return err
	}
	mixID, _ := f[2].uint()
	n, _ := f[4].uint()
	if n > 64 {
		return errors.New("pipewire: too many buffers")
	}
	rest := f[5:]
	take := func() (uint32, error) {
		if len(rest) == 0 {
			return 0, errors.New("pipewire: port_use_buffers too short")
		}
		v := rest[0]
		rest = rest[1:]
		return v.uint()
	}
	var bufs []*buffer
	for i := uint32(0); i < n; i++ {
		memid, err := take()
		if err != nil {
			return err
		}
		off, _ := take()
		size, _ := take()
		nMetas, err := take()
		if err != nil || nMetas > 16 {
			return errors.New("pipewire: bad buffer metadata")
		}
		mb, err := s.conn.mem(memid)
		if err != nil {
			return err
		}
		area, err := mb.bytes(off, size)
		if err != nil {
			return err
		}
		b := &buffer{}
		pos := uint32(0)
		for j := uint32(0); j < nMetas; j++ {
			typ, _ := take()
			msize, err := take()
			if err != nil {
				return err
			}
			if uint64(pos)+uint64(msize) > uint64(len(area)) {
				return errors.New("pipewire: buffer metadata out of bounds")
			}
			b.metas = append(b.metas, bufMeta{typ: typ, data: area[pos : pos+msize]})
			pos += (msize + 7) &^ 7
		}
		nDatas, err := take()
		if err != nil || nDatas > 4 {
			return errors.New("pipewire: bad buffer data")
		}
		for j := uint32(0); j < nDatas; j++ {
			typ, _ := take()
			dataID, _ := take()
			_, _ = take() // flags
			mapoff, _ := take()
			maxsize, err := take()
			if err != nil {
				return err
			}
			coff := pos + 16*j
			if uint64(coff)+16 > uint64(len(area)) {
				return errors.New("pipewire: buffer chunk out of bounds")
			}
			d := bufData{typ: typ, chunk: area[coff : coff+16]}
			switch typ {
			case dataMemID:
				dm, err := s.conn.mem(dataID)
				if err != nil {
					return err
				}
				if d.data, err = dm.bytes(mapoff, maxsize); err != nil {
					return err
				}
				d.typ = dm.typ
			case dataMemPtr:
				if uint64(dataID)+uint64(maxsize) > uint64(len(area)) {
					return errors.New("pipewire: buffer data out of bounds")
				}
				d.data = area[dataID : dataID+maxsize]
			default:
				return fmt.Errorf("pipewire: buffer data of type %d (DMA-BUF?) cannot be read", typ)
			}
			b.datas = append(b.datas, d)
		}
		bufs = append(bufs, b)
	}
	s.rtMu.Lock()
	s.mixFor(mixID).buffers = bufs
	s.rtMu.Unlock()
	if len(bufs) > 0 {
		debugf("pipewire: mix %d: %d buffers, data type %d, %d bytes, %d metas", mixID, len(bufs), bufs[0].datas[0].typ, len(bufs[0].datas[0].data), len(bufs[0].metas))
	}
	return nil
}

// run is the processing loop: it waits for the graph to signal the node's
// eventfd and runs one cycle each time.
func (s *Stream) run() {
	defer func() {
		s.rtMu.Lock()
		s.closeRT()
		s.rtMu.Unlock()
	}()
	var tmp [8]byte
	for {
		select {
		case <-s.done:
			return
		default:
		}
		s.rtMu.Lock()
		fd := s.readfd
		s.rtMu.Unlock()
		if fd < 0 {
			return
		}
		pfd := []unix.PollFd{{Fd: int32(fd), Events: unix.POLLIN}}
		n, err := unix.Poll(pfd, 200)
		if err != nil && !errors.Is(err, unix.EINTR) {
			s.fail(fmt.Errorf("pipewire: poll: %w", err))
			return
		}
		if n <= 0 || pfd[0].Revents&unix.POLLIN == 0 {
			if pfd[0].Revents&(unix.POLLERR|unix.POLLHUP) != 0 {
				return
			}
			continue
		}
		if _, err := unix.Read(fd, tmp[:]); err != nil {
			continue
		}
		s.rtMu.Lock()
		s.cycle()
		s.rtMu.Unlock()
	}
}

func u32at(b []byte, off int) *uint32 { return (*uint32)(unsafe.Pointer(&b[off])) }
func i32at(b []byte, off int) *int32  { return (*int32)(unsafe.Pointer(&b[off])) }

// cycle is one processing cycle, as PipeWire's process_node does it for a
// node in another process. Called under rtMu.
func (s *Stream) cycle() {
	a := s.activation
	if a == nil || !atomic.CompareAndSwapUint32(u32at(a, actStatus), actTriggered, actAwake) {
		if s.cycles < 5 && a != nil {
			debugf("pipewire: woken with status %d", atomic.LoadUint32(u32at(a, actStatus)))
		}
		return
	}
	s.cycles++
	var cycle uint32
	if len(s.position) >= positionCycle+4 {
		cycle = atomic.LoadUint32(u32at(s.position, positionCycle))
	}
	for _, mx := range s.mixes {
		if mx.io == nil {
			continue
		}
		io := mx.io
		if mx.async {
			io = io[(cycle&1)*8:]
		}
		status := atomic.LoadInt32(i32at(io, 0))
		id := atomic.LoadUint32(u32at(io, 4))
		if s.cycles < 5 || s.cycles%300 == 0 {
			debugf("pipewire: cycle %d (graph %d): io status %d buffer %d of %d, async %v", s.cycles, cycle, status, id, len(mx.buffers), mx.async)
		}
		if status == statusHaveData && id < uint32(len(mx.buffers)) {
			s.consume(mx.buffers[id])
		}
		atomic.StoreInt32(i32at(io, 0), statusNeedData)
	}
	atomic.StoreInt32(i32at(a, actState0Status), statusNeedData|statusHaveData)
	awake := atomic.CompareAndSwapUint32(u32at(a, actStatus), actAwake, actFinished)
	async := atomic.LoadUint32(u32at(a, actFlags))&actFlagAsync != 0
	if awake && (!async || atomic.LoadUint32(u32at(a, actServerVersion)) < 1) {
		for _, p := range s.peers {
			p.trigger()
		}
	}
}

// trigger tells a peer this node is done with the cycle, and wakes it when it
// was the last one it waited for.
func (p *peer) trigger() {
	if atomic.AddInt32(i32at(p.act, actStatePending), -1) != 0 {
		return
	}
	if atomic.LoadUint32(u32at(p.act, actServerVersion)) >= 1 {
		if !atomic.CompareAndSwapUint32(u32at(p.act, actStatus), actNotTriggered, actTriggered) {
			return
		}
	} else {
		atomic.StoreUint32(u32at(p.act, actStatus), actTriggered)
	}
	var one [8]byte
	binary.NativeEndian.PutUint64(one[:], 1)
	_, _ = unix.Write(p.fd, one[:])
}

// consume takes one buffer's frame and metadata. Called under rtMu.
func (s *Stream) consume(b *buffer) {
	if !s.formatSet || len(b.datas) == 0 {
		return
	}
	var (
		cursor = -1
		crop   []byte
		damage []byte
	)
	for i, m := range b.metas {
		switch m.typ {
		case metaHeader:
			if len(m.data) >= 4 && le.Uint32(m.data)&headerCorrupted != 0 {
				if s.cycles < 5 {
					debugf("pipewire: frame dropped: header flags %#x", le.Uint32(m.data))
				}
				return
			}
		case metaCursor:
			cursor = i
		case metaVideoCrop:
			crop = m.data
		case metaDamage:
			damage = m.data
		}
	}
	if cursor >= 0 {
		s.readCursor(b.metas[cursor].data)
	}

	d := b.datas[0]
	coff := le.Uint32(d.chunk)
	csize := le.Uint32(d.chunk[4:])
	stride := int(int32(le.Uint32(d.chunk[8:])))
	cflags := le.Uint32(d.chunk[12:])
	if cflags&chunkCorrupted != 0 || csize == 0 {
		if s.cycles < 5 {
			debugf("pipewire: frame dropped: chunk offset %d size %d stride %d flags %#x", coff, csize, stride, cflags)
		}
		return // a cursor-only update, or a frame to drop
	}
	f := s.format
	if stride <= 0 {
		stride = f.Width * 4
	}
	x0, y0, w, h := 0, 0, f.Width, f.Height
	if len(crop) >= 16 {
		cx, cy := int(int32(le.Uint32(crop))), int(int32(le.Uint32(crop[4:])))
		cw, ch := int(le.Uint32(crop[8:])), int(le.Uint32(crop[12:]))
		if cw > 0 && ch > 0 && cx >= 0 && cy >= 0 && cx+cw <= f.Width && cy+ch <= f.Height {
			x0, y0, w, h = cx, cy, cw, ch
		}
	}
	src := d.data
	if uint64(coff)+uint64(csize) > uint64(len(src)) {
		return
	}
	src = src[coff : coff+csize]
	if (y0+h-1)*stride+(x0+w)*4 > len(src) {
		return
	}

	s.frameMu.Lock()
	defer s.frameMu.Unlock()
	rects, known := parseDamage(damage, x0, y0, w, h)
	if s.frameW != w || s.frameH != h || s.frameF != f.Format || len(s.frame) != w*h*4 {
		s.frame = make([]byte, w*h*4)
		s.frameW, s.frameH, s.frameF = w, h, f.Format
		known = false
	}
	if !known {
		rects = []Rect{{0, 0, w, h}}
	}
	row := w * 4
	for _, r := range rects {
		for y := r.Y; y < r.Y+r.H; y++ {
			so := (y0+y)*stride + (x0+r.X)*4
			copy(s.frame[y*row+r.X*4:y*row+(r.X+r.W)*4], src[so:so+r.W*4])
		}
	}
	if !known {
		s.damageK = false
	} else if s.damageK {
		s.damage = append(s.damage, rects...)
	}
	s.frames++
	select {
	case s.newFrame <- struct{}{}:
	default:
	}
}

// parseDamage reads a VideoDamage meta (spa_meta_region items, ended by an
// empty one) as rectangles of the cropped frame. known is false when there
// is no damage meta or it does not say.
func parseDamage(m []byte, x0, y0, w, h int) ([]Rect, bool) {
	if len(m) < 16 {
		return nil, false
	}
	var out []Rect
	for off := 0; off+16 <= len(m); off += 16 {
		x, y := int(int32(le.Uint32(m[off:]))), int(int32(le.Uint32(m[off+4:])))
		rw, rh := int(le.Uint32(m[off+8:])), int(le.Uint32(m[off+12:]))
		if rw == 0 || rh == 0 {
			break
		}
		x -= x0
		y -= y0
		x1, y1 := min(x+rw, w), min(y+rh, h)
		x, y = max(x, 0), max(y, 0)
		if x1 > x && y1 > y {
			out = append(out, Rect{x, y, x1 - x, y1 - y})
		}
	}
	if len(out) == 0 {
		return nil, false
	}
	return out, true
}

// readCursor reads a spa_meta_cursor. Called under rtMu.
func (s *Stream) readCursor(m []byte) {
	if len(m) < 28 {
		return
	}
	id := le.Uint32(m)
	s.frameMu.Lock()
	defer s.frameMu.Unlock()
	c := &s.cursor
	if id == 0 {
		c.Visible = false
		return
	}
	c.Visible = true
	c.X, c.Y = int(int32(le.Uint32(m[8:]))), int(int32(le.Uint32(m[12:])))
	c.HotX, c.HotY = int(int32(le.Uint32(m[16:]))), int(int32(le.Uint32(m[20:])))
	boff := int(le.Uint32(m[24:]))
	if boff < 28 || boff+20 > len(m) {
		return
	}
	bm := m[boff:]
	format := le.Uint32(bm)
	bw, bh := int(le.Uint32(bm[4:])), int(le.Uint32(bm[8:]))
	stride := int(int32(le.Uint32(bm[12:])))
	poff := int(le.Uint32(bm[16:]))
	if bw <= 0 || bh <= 0 || bw > 512 || bh > 512 {
		return // unchanged image
	}
	if stride <= 0 {
		stride = bw * 4
	}
	if poff < 20 || poff+(bh-1)*stride+bw*4 > len(bm) {
		return
	}
	px := make([]uint32, bw*bh)
	for y := 0; y < bh; y++ {
		for x := 0; x < bw; x++ {
			o := poff + y*stride + x*4
			px[y*bw+x] = toARGB(format, le.Uint32(bm[o:]))
		}
	}
	c.Width, c.Height, c.Pixels = bw, bh, px
	c.Serial++
}

// toARGB turns a little-endian loaded pixel of format into 0xAARRGGBB; the
// x formats come out opaque.
func toARGB(format, v uint32) uint32 {
	switch format {
	case FormatBGRA:
		return v
	case FormatBGRx:
		return v | 0xff000000
	case FormatRGBA:
		return v&0xff00ff00 | (v&0xff)<<16 | (v>>16)&0xff
	case FormatRGBx:
		return 0xff000000 | (v&0xff)<<16 | v&0xff00 | (v>>16)&0xff
	case FormatARGB:
		return (v&0xff)<<24 | (v>>8)&0xff<<16 | (v>>16)&0xff<<8 | v>>24
	case FormatXRGB:
		return 0xff000000 | (v>>8)&0xff<<16 | (v>>16)&0xff<<8 | v>>24
	case FormatABGR:
		return (v&0xff)<<24 | v>>8
	case FormatXBGR:
		return 0xff000000 | v>>8
	}
	return v
}

// Size is the size of the frames, from the last frame received (0 before
// the first).
func (s *Stream) Size() (int, int) {
	s.frameMu.Lock()
	defer s.frameMu.Unlock()
	return s.frameW, s.frameH
}

// WaitFrame waits until the stream has a frame, the stream ends or timeout.
func (s *Stream) WaitFrame(timeout time.Duration) error {
	t := time.NewTimer(timeout)
	defer t.Stop()
	for {
		s.frameMu.Lock()
		n := s.frames
		s.frameMu.Unlock()
		if n > 0 {
			return nil
		}
		select {
		case <-s.newFrame:
		case <-s.done:
			return s.err
		case <-t.C:
			return errors.New("pipewire: no frame arrived from the screen")
		}
	}
}

// Read converts the last frame into dst (w×h, 0x00RRGGBB): false when the
// last frame has another size.
func (s *Stream) Read(dst []uint32, w, h int) bool {
	s.frameMu.Lock()
	defer s.frameMu.Unlock()
	if s.frameW != w || s.frameH != h || len(dst) < w*h {
		return false
	}
	src := s.frame
	n := w * h
	switch s.frameF {
	case FormatBGRx, FormatBGRA:
		for i := 0; i < n; i++ {
			dst[i] = le.Uint32(src[i*4:]) & 0xffffff
		}
	default:
		for i := 0; i < n; i++ {
			dst[i] = toARGB(s.frameF, le.Uint32(src[i*4:])) & 0xffffff
		}
	}
	s.readDamage = append(s.readDamage[:0], s.damage...)
	s.readDamageK = s.damageK && s.readOnce
	s.readOnce = true
	s.damage = s.damage[:0]
	s.damageK = true
	return true
}

// Damage returns what the last Read changed against the Read before it, in
// frame pixels; known is false when the producer did not say for every frame
// in between (compare everything).
func (s *Stream) Damage() ([]Rect, bool) {
	s.frameMu.Lock()
	defer s.frameMu.Unlock()
	if !s.readDamageK {
		return nil, false
	}
	return append([]Rect(nil), s.readDamage...), true
}

// Cursor is the cursor as last reported.
func (s *Stream) Cursor() Cursor {
	s.frameMu.Lock()
	defer s.frameMu.Unlock()
	return s.cursor
}
