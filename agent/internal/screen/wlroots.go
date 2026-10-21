//go:build linux

package screen

import (
	"errors"
	"fmt"
	"image"
	"math"
	"slices"
	"sort"
	"sync"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/wayland"
)

// ErrNoWlrootsCapture reports a compositor without ext-image-copy-capture or
// wlr-screencopy.
var ErrNoWlrootsCapture = errors.New("screen: the compositor offers neither ext-image-copy-capture nor wlr-screencopy")

// firstFrameTimeout bounds the wait for a monitor's first image.
var firstFrameTimeout = 5 * time.Second

// Wlroots captures every output of a Wayland compositor of the wlroots
// family (ADR-0031 §2) as one image, the whole desktop: through
// ext-image-copy-capture-v1 when it has it (with the cursor in its own
// capture session, drawn by the client), else wlr-screencopy-unstable-v1
// (cursor painted into the image). Both copy into shm buffers and report the
// damage, so Serve compares only what changed (Damager).
//
// The monitors sit as the compositor lays them out, in logical pixels times
// the largest scale, so a HiDPI monitor keeps its physical pixels and none
// overlaps; PointerTarget takes a point of the image back to the layout for
// the virtual pointer.
type Wlroots struct {
	c       *wayland.Conn
	outs    *wayland.Outputs
	shm     uint32
	extMgr  uint32 // ext_image_copy_capture_manager_v1, 0 for screencopy
	srcMgr  uint32 // ext_output_image_capture_source_manager_v1
	copyMgr uint32 // zwlr_screencopy_manager_v1
	copyVer uint32
	pointer uint32 // wl_pointer for the cursor sessions, 0 to paint it

	mu       sync.Mutex
	gen      uint64
	started  bool
	monitors map[uint32]*monitor // by output global
	parts    []*monitor          // in Composite order
	comp     Composite
}

// NewWlroots prepares the capture on c. pointer is a wl_pointer of the seat
// for the cursor capture sessions, or 0 to have the cursor painted into the
// image.
func NewWlroots(c *wayland.Conn, pointer uint32) (*Wlroots, error) {
	w := &Wlroots{c: c, pointer: pointer, monitors: map[uint32]*monitor{}}
	g, ok := c.Find("wl_shm")
	if !ok {
		return nil, errors.New("screen: the compositor has no wl_shm")
	}
	var err error
	if w.shm, _, err = c.Bind(g, 1, nil); err != nil {
		return nil, err
	}
	ext, okExt := c.Find("ext_image_copy_capture_manager_v1")
	src, okSrc := c.Find("ext_output_image_capture_source_manager_v1")
	switch {
	case okExt && okSrc:
		if w.extMgr, _, err = c.Bind(ext, 1, nil); err != nil {
			return nil, err
		}
		if w.srcMgr, _, err = c.Bind(src, 1, nil); err != nil {
			return nil, err
		}
	default:
		g, ok := c.Find("zwlr_screencopy_manager_v1")
		if !ok {
			return nil, ErrNoWlrootsCapture
		}
		if w.copyMgr, w.copyVer, err = c.Bind(g, 3, nil); err != nil {
			return nil, err
		}
		w.pointer = 0
	}
	if w.outs, err = wayland.WatchOutputs(c, nil); err != nil {
		return nil, err
	}
	return w, nil
}

// Protocol says which capture protocol is in use.
func (w *Wlroots) Protocol() string {
	if w.extMgr != 0 {
		if w.pointer != 0 {
			return "ext-image-copy-capture-v1 (cursor session)"
		}
		return "ext-image-copy-capture-v1"
	}
	return fmt.Sprintf("wlr-screencopy-unstable-v1 v%d", w.copyVer)
}

// refresh follows the outputs: a capture for each new one, none for those
// gone, and the parts laid out again. Called under mu.
func (w *Wlroots) refresh() error {
	outs, gen := w.outs.List()
	if w.started && gen == w.gen {
		return nil
	}
	if len(outs) == 0 {
		return errors.New("screen: the compositor has no outputs")
	}
	w.started, w.gen = true, gen
	seen := map[uint32]bool{}
	for _, o := range outs {
		seen[o.Global] = true
	}
	for g, m := range w.monitors {
		if !seen[g] {
			m.stop()
			delete(w.monitors, g)
		}
	}
	xs, ys := layout(outs)
	w.parts = w.parts[:0]
	w.comp.Parts = w.comp.Parts[:0]
	for i, o := range outs {
		m := w.monitors[o.Global]
		if m == nil {
			m = newMonitor(w, o)
			w.monitors[o.Global] = m
			if err := m.start(); err != nil {
				return err
			}
		}
		m.setOutput(o)
		w.parts = append(w.parts, m)
		w.comp.Parts = append(w.comp.Parts, Part{X: xs[i], Y: ys[i], Capturer: m})
	}
	return nil
}

// layout places the outputs in the image: in their logical layout, each
// stretch of it as wide (and tall) as the most physical pixels any output
// over it has. Side by side monitors stay side by side with their own
// pixels, whatever their scales, without gaps or overlaps.
func layout(outs []wayland.Output) (xs, ys []int) {
	axis := func(lo, hi func(wayland.Output) int, size func(wayland.Output) int) []int {
		var edges []int
		for _, o := range outs {
			edges = append(edges, lo(o), hi(o))
		}
		sort.Ints(edges)
		edges = slices.Compact(edges)
		pos := map[int]float64{edges[0]: 0}
		at := 0.0
		for i := 1; i < len(edges); i++ {
			a, b := edges[i-1], edges[i]
			stretch := 0.0
			for _, o := range outs {
				if lo(o) <= a && hi(o) >= b && hi(o) > lo(o) {
					stretch = max(stretch, float64(b-a)*float64(size(o))/float64(hi(o)-lo(o)))
				}
			}
			at += math.Ceil(stretch)
			pos[b] = at
		}
		out := make([]int, len(outs))
		for i, o := range outs {
			out[i] = int(pos[lo(o)])
		}
		return out
	}
	phys := func(o wayland.Output) (int, int) {
		if o.Transform&1 == 1 {
			return o.Height, o.Width
		}
		return o.Width, o.Height
	}
	xs = axis(func(o wayland.Output) int { return o.Logical.Min.X }, func(o wayland.Output) int { return o.Logical.Max.X },
		func(o wayland.Output) int { w, _ := phys(o); return w })
	ys = axis(func(o wayland.Output) int { return o.Logical.Min.Y }, func(o wayland.Output) int { return o.Logical.Max.Y },
		func(o wayland.Output) int { _, h := phys(o); return h })
	return xs, ys
}

// Size is the whole desktop's size.
func (w *Wlroots) Size() (int, int, error) {
	if err := w.c.Err(); err != nil {
		return 0, 0, err
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	if err := w.refresh(); err != nil {
		return 0, 0, err
	}
	return w.comp.Size()
}

// Capture reads every monitor into its place.
func (w *Wlroots) Capture(dst []uint32, width, height int) error {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.comp.Capture(dst, width, height)
}

// Cursor is the cursor of the monitor that shows it.
func (w *Wlroots) Cursor() (Cursor, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.comp.Cursor()
}

// Damage is what changed since the last Capture, on every monitor.
func (w *Wlroots) Damage() ([]image.Rectangle, bool) {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.comp.Damage()
}

// PointerTarget takes x, y of the captured image to the compositor's layout
// as zwlr_virtual_pointer_v1.motion_absolute wants it: a point and the
// extent of the whole layout, in the same units. ok is false between
// monitors.
func (w *Wlroots) PointerTarget(x, y int) (px, py, xExtent, yExtent uint32, ok bool) {
	w.mu.Lock()
	defer w.mu.Unlock()
	i, mx, my, ok := w.comp.Locate(x, y)
	if !ok || i >= len(w.parts) {
		return 0, 0, 0, 0, false
	}
	var box image.Rectangle
	for _, m := range w.parts {
		box = box.Union(m.output().Logical)
	}
	m := w.parts[i]
	l := m.output().Logical
	bw, bh := m.imageSize()
	if bw <= 0 || bh <= 0 || box.Empty() {
		return 0, 0, 0, 0, false
	}
	// The point in logical pixels is l.Min + m*l.Size/b; scaled by b to stay
	// in integers.
	px = uint32((l.Min.X-box.Min.X)*bw + mx*l.Dx())
	py = uint32((l.Min.Y-box.Min.Y)*bh + my*l.Dy())
	return px, py, uint32(box.Dx() * bw), uint32(box.Dy() * bh), true
}

// monitor captures one output.
type monitor struct {
	w   *Wlroots
	mu  sync.Mutex
	out wayland.Output

	// The image as last copied, its pending damage and whether some frame
	// arrived yet.
	img      []uint32
	iw, ih   int
	damage   []image.Rectangle
	frames   int
	stopped  bool
	err      error
	arrived  chan struct{} // closed on the first frame or a failure
	arriveOK bool

	main *extCapture // ext-image-copy-capture
	// The cursor through its own session: whether it is over this output,
	// where its hot spot is, and its image.
	cursorOn    *extCapture
	cursorIn    bool
	cursorX     int
	cursorY     int
	hotX, hotY  int
	nextHot     [2]int
	cursorImg   []uint32
	cursorW     int
	cursorH     int
	cursorSer   uint32
	cursorSess  uint32
	sourceID    uint32
	copyFrame   uint32 // screencopy: the frame in flight
	copyState   screencopyFrame
	copyBuf     *wayland.ShmBuffer
	copyFirst   bool
	copyFails   int
	copyPending bool
}

func newMonitor(w *Wlroots, o wayland.Output) *monitor {
	return &monitor{w: w, out: o, arrived: make(chan struct{}), copyFirst: true}
}

func (m *monitor) setOutput(o wayland.Output) {
	m.mu.Lock()
	m.out = o
	m.mu.Unlock()
}

func (m *monitor) output() wayland.Output {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.out
}

func (m *monitor) imageSize() (int, int) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.iw, m.ih
}

// start begins capturing.
func (m *monitor) start() error {
	c := m.w.c
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.w.extMgr == 0 {
		return m.armCopy()
	}
	m.sourceID = c.NewID(nil)
	if err := c.Request(m.w.srcMgr, 0, wayland.NewID(m.sourceID), wayland.Object(m.out.ID)); err != nil { // create_source
		return err
	}
	var options uint32 = 1 // paint_cursors
	if m.w.pointer != 0 {
		options = 0
	}
	m.main = newExtCapture(m, m.mainReady, m.mainSize)
	if err := c.Request(m.w.extMgr, 0, wayland.NewID(m.main.session), wayland.Object(m.sourceID), options); err != nil { // create_session
		return err
	}
	if m.w.pointer == 0 {
		return nil
	}
	m.cursorSess = c.NewID(m.cursorEvent)
	if err := c.Request(m.w.extMgr, 1, wayland.NewID(m.cursorSess), wayland.Object(m.sourceID), wayland.Object(m.w.pointer)); err != nil { // create_pointer_cursor_session
		return err
	}
	m.cursorOn = newExtCapture(m, m.cursorReady, nil)
	return c.Request(m.cursorSess, 1, wayland.NewID(m.cursorOn.session)) // get_capture_session
}

// stop ends the capture of an output that went away.
func (m *monitor) stop() {
	c := m.w.c
	m.mu.Lock()
	defer m.mu.Unlock()
	m.stopped = true
	if m.main != nil {
		m.main.destroy()
	}
	if m.cursorOn != nil {
		m.cursorOn.destroy()
	}
	if m.cursorSess != 0 {
		_ = c.Request(m.cursorSess, 0)
		c.Forget(m.cursorSess)
	}
	if m.sourceID != 0 {
		_ = c.Request(m.sourceID, 0)
		c.Forget(m.sourceID)
	}
	if m.copyFrame != 0 {
		_ = c.Request(m.copyFrame, 1)
		c.Forget(m.copyFrame)
		m.copyFrame = 0
	}
	if m.copyBuf != nil {
		m.copyBuf.Destroy()
		m.copyBuf = nil
	}
	m.arrive(false)
}

// arrive marks the first frame (or the failure before one). Under mu.
func (m *monitor) arrive(ok bool) {
	select {
	case <-m.arrived:
	default:
		m.arriveOK = ok
		close(m.arrived)
	}
}

// resize makes the image w×h, black and wholly damaged. Under mu.
func (m *monitor) resize(w, h int) {
	if w == m.iw && h == m.ih {
		return
	}
	m.iw, m.ih = w, h
	m.img = make([]uint32, w*h)
	m.damage = append(m.damage[:0], image.Rect(0, 0, w, h))
}

func (m *monitor) mainSize(w, h int) { m.resize(w, h) }

// mainReady copies a new frame's damage into the image. Under mu.
func (m *monitor) mainReady(b *wayland.ShmBuffer, rects []image.Rectangle) {
	m.resize(b.Width, b.Height)
	for _, r := range rects {
		r = convert(m.img, m.iw, b, r, false)
		if !r.Empty() {
			m.damage = append(m.damage, r)
		}
	}
	m.frames++
	m.arrive(true)
}

// cursorReady takes a new cursor image. Under mu.
func (m *monitor) cursorReady(b *wayland.ShmBuffer, _ []image.Rectangle) {
	if b.Width*b.Height == 0 {
		return
	}
	img := make([]uint32, b.Width*b.Height)
	convertARGB(img, b)
	for i, p := range img {
		img[i] = unpremultiply(p)
	}
	m.cursorImg, m.cursorW, m.cursorH = img, b.Width, b.Height
	m.hotX, m.hotY = m.nextHot[0], m.nextHot[1]
	m.cursorSer++
}

// cursorEvent handles ext_image_copy_capture_cursor_session_v1.
func (m *monitor) cursorEvent(op uint16, msg *wayland.Message) {
	m.mu.Lock()
	defer m.mu.Unlock()
	switch op {
	case 0: // enter
		m.cursorIn = true
	case 1: // leave
		m.cursorIn = false
	case 2: // position
		m.cursorX, m.cursorY = int(msg.Int()), int(msg.Int())
	case 3: // hotspot
		m.nextHot = [2]int{int(msg.Int()), int(msg.Int())}
	}
}

// Size waits for the first frame, then gives the image's size.
func (m *monitor) Size() (int, int, error) {
	select {
	case <-m.arrived:
	case <-time.After(firstFrameTimeout):
		return 0, 0, fmt.Errorf("screen: no image of output %s after %v", m.output().Name, firstFrameTimeout)
	case <-m.w.c.Done():
		return 0, 0, m.w.c.Err()
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if !m.arriveOK {
		if m.err != nil {
			return 0, 0, m.err
		}
		return 0, 0, fmt.Errorf("screen: the capture of output %s stopped", m.out.Name)
	}
	return m.iw, m.ih, nil
}

// Capture copies the last image.
func (m *monitor) Capture(dst []uint32, w, h int) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.err != nil {
		return m.err
	}
	for y := 0; y < min(h, m.ih); y++ {
		copy(dst[y*w:y*w+min(w, m.iw)], m.img[y*m.iw:])
	}
	// wlr-screencopy version 1 has no damage to wait for: one copy per
	// capture.
	if m.w.copyMgr != 0 && m.w.copyVer < 2 && m.copyFrame == 0 {
		return m.armCopy()
	}
	return nil
}

// Damage gives what changed since the last Damage and starts over.
func (m *monitor) Damage() ([]image.Rectangle, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	d := m.damage
	m.damage = nil
	return d, true
}

// Cursor reports the cursor from its own session; with the cursor painted
// into the image there is none to report.
func (m *monitor) Cursor() (Cursor, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.cursorOn == nil || !m.cursorIn || m.cursorImg == nil {
		return Cursor{Serial: m.cursorSer}, nil
	}
	return Cursor{
		X: m.cursorX, Y: m.cursorY, Visible: true, Serial: m.cursorSer,
		Width: m.cursorW, Height: m.cursorH, HotX: m.hotX, HotY: m.hotY, Pixels: m.cursorImg,
	}, nil
}

// extCapture is one ext_image_copy_capture_session_v1: its buffer
// constraints, its shm buffer and the frame in flight. A new frame is asked
// for as soon as one is ready; the compositor sends it when the source
// changes.
type extCapture struct {
	m       *monitor
	session uint32
	onReady func(*wayland.ShmBuffer, []image.Rectangle)
	onSize  func(w, h int)

	pw, ph   int
	pformats []uint32
	buf      *wayland.ShmBuffer
	fresh    bool // the buffer was never captured into
	realloc  bool // constraints changed while a frame was in flight
	frame    uint32
	fdamage  []image.Rectangle
	fails    int
	stopped  bool
}

func newExtCapture(m *monitor, onReady func(*wayland.ShmBuffer, []image.Rectangle), onSize func(int, int)) *extCapture {
	e := &extCapture{m: m, onReady: onReady, onSize: onSize}
	e.session = m.w.c.NewID(e.sessionEvent)
	return e
}

func (e *extCapture) sessionEvent(op uint16, msg *wayland.Message) {
	e.m.mu.Lock()
	defer e.m.mu.Unlock()
	switch op {
	case 0: // buffer_size
		e.pw, e.ph = int(msg.Uint()), int(msg.Uint())
	case 1: // shm_format
		e.pformats = append(e.pformats, msg.Uint())
	case 4: // done
		if e.frame != 0 {
			e.realloc = true
		} else {
			e.allocate()
			e.arm()
		}
	case 5: // stopped
		e.stopped = true
		if e == e.m.main {
			e.m.arrive(false)
		}
	}
}

// allocate makes the buffer fit the constraints. Under mu.
func (e *extCapture) allocate() {
	formats := e.pformats
	e.pformats = nil
	e.realloc = false
	if e.pw <= 0 || e.ph <= 0 {
		return
	}
	format, ok := pickFormat(formats)
	if !ok {
		if e == e.m.main {
			e.m.err = fmt.Errorf("screen: output %s offers no shm format the agent reads (%#x)", e.m.out.Name, formats)
			e.m.arrive(false)
		}
		return
	}
	if e.buf != nil && e.buf.Width == e.pw && e.buf.Height == e.ph && e.buf.Format == format {
		return
	}
	if e.buf != nil {
		e.buf.Destroy()
		e.buf = nil
	}
	b, err := wayland.NewShmBuffer(e.m.w.c, e.m.w.shm, e.pw, e.ph, e.pw*4, format)
	if err != nil {
		e.m.err = err
		e.m.arrive(false)
		return
	}
	e.buf, e.fresh = b, true
	if e.onSize != nil {
		e.onSize(e.pw, e.ph)
	}
}

// arm asks for the next frame. Under mu.
func (e *extCapture) arm() {
	if e.frame != 0 || e.buf == nil || e.stopped || e.m.stopped {
		return
	}
	c := e.m.w.c
	e.frame = c.NewID(e.frameEvent)
	e.fdamage = e.fdamage[:0]
	_ = c.Request(e.session, 0, wayland.NewID(e.frame)) // create_frame
	_ = c.Request(e.frame, 1, wayland.Object(e.buf.ID)) // attach_buffer
	if e.fresh {
		_ = c.Request(e.frame, 2, int32(0), int32(0), int32(e.buf.Width), int32(e.buf.Height)) // damage_buffer
		e.fresh = false
	}
	_ = c.Request(e.frame, 3) // capture
}

func (e *extCapture) endFrame() {
	c := e.m.w.c
	_ = c.Request(e.frame, 0) // destroy
	c.Forget(e.frame)
	e.frame = 0
}

func (e *extCapture) frameEvent(op uint16, msg *wayland.Message) {
	e.m.mu.Lock()
	defer e.m.mu.Unlock()
	switch op {
	case 1: // damage
		x, y, w, h := int(msg.Int()), int(msg.Int()), int(msg.Int()), int(msg.Int())
		e.fdamage = append(e.fdamage, image.Rect(x, y, x+w, y+h))
	case 3: // ready
		e.endFrame()
		e.fails = 0
		if !e.m.stopped {
			e.onReady(e.buf, e.fdamage)
		}
	case 4: // failed
		reason := msg.Uint()
		e.endFrame()
		switch reason {
		case 1: // buffer_constraints: the done that follows reallocates
			e.realloc = true
			return
		case 2: // stopped
			e.stopped = true
			if e == e.m.main {
				e.m.arrive(false)
			}
			return
		}
		e.fails++
		if e.fails > 10 && e == e.m.main {
			e.m.err = fmt.Errorf("screen: the compositor keeps failing to capture output %s", e.m.out.Name)
			e.m.arrive(false)
			return
		}
		e.fresh = true
	default:
		return
	}
	if e.realloc {
		e.allocate()
	}
	e.arm()
}

func (e *extCapture) destroy() {
	c := e.m.w.c
	if e.frame != 0 {
		e.endFrame()
	}
	_ = c.Request(e.session, 1) // destroy
	c.Forget(e.session)
	if e.buf != nil {
		e.buf.Destroy()
		e.buf = nil
	}
}

// screencopyFrame is what a zwlr_screencopy_frame_v1 said before its copy.
type screencopyFrame struct {
	format       uint32
	w, h, stride int
	ok           bool
	yInvert      bool
	damage       []image.Rectangle
	copied       bool
}

// armCopy asks wlr-screencopy for the next frame, cursor painted in. Under mu.
func (m *monitor) armCopy() error {
	if m.copyFrame != 0 || m.stopped {
		return nil
	}
	c := m.w.c
	m.copyFrame = c.NewID(m.copyEvent)
	m.copyState = screencopyFrame{}
	return c.Request(m.w.copyMgr, 0, wayland.NewID(m.copyFrame), int32(1), wayland.Object(m.out.ID)) // capture_output
}

func (m *monitor) copyEvent(op uint16, msg *wayland.Message) {
	m.mu.Lock()
	defer m.mu.Unlock()
	c := m.w.c
	st := &m.copyState
	switch op {
	case 0: // buffer
		format, w, h, stride := msg.Uint(), int(msg.Uint()), int(msg.Uint()), int(msg.Uint())
		if _, ok := pickFormat([]uint32{format}); ok && (!st.ok || preferFormat(format, st.format)) {
			st.format, st.w, st.h, st.stride, st.ok = format, w, h, stride, true
		}
		if m.w.copyVer < 3 {
			m.copyNow()
		}
	case 6: // buffer_done
		m.copyNow()
	case 1: // flags
		st.yInvert = msg.Uint()&1 != 0
	case 4: // damage
		x, y, w, h := int(msg.Uint()), int(msg.Uint()), int(msg.Uint()), int(msg.Uint())
		st.damage = append(st.damage, image.Rect(x, y, x+w, y+h))
	case 2: // ready
		b := m.copyBuf
		_ = c.Request(m.copyFrame, 1) // destroy
		c.Forget(m.copyFrame)
		m.copyFrame = 0
		m.copyFails = 0
		rects := st.damage
		if m.copyFirst || m.w.copyVer < 2 || st.yInvert {
			rects = []image.Rectangle{image.Rect(0, 0, b.Width, b.Height)}
			m.copyFirst = false
		}
		m.resize(b.Width, b.Height)
		for _, r := range rects {
			r = convert(m.img, m.iw, b, r, st.yInvert)
			if !r.Empty() {
				m.damage = append(m.damage, r)
			}
		}
		m.frames++
		m.arrive(true)
		if m.w.copyVer >= 2 {
			_ = m.armCopy()
		}
	case 3: // failed
		_ = c.Request(m.copyFrame, 1) // destroy
		c.Forget(m.copyFrame)
		m.copyFrame = 0
		m.copyFails++
		if m.copyFails > 10 {
			m.err = fmt.Errorf("screen: the compositor keeps failing to capture output %s", m.out.Name)
			m.arrive(false)
			return
		}
		m.copyFirst = true
		_ = m.armCopy()
	}
}

// copyNow sends the copy once the buffer is known. Under mu.
func (m *monitor) copyNow() {
	st := &m.copyState
	if st.copied || m.copyFrame == 0 {
		return
	}
	if !st.ok {
		m.err = fmt.Errorf("screen: output %s offers no shm format the agent reads", m.out.Name)
		m.arrive(false)
		return
	}
	st.copied = true
	if b := m.copyBuf; b == nil || b.Width != st.w || b.Height != st.h || b.Stride != st.stride || b.Format != st.format {
		if b != nil {
			b.Destroy()
		}
		nb, err := wayland.NewShmBuffer(m.w.c, m.w.shm, st.w, st.h, st.stride, st.format)
		if err != nil {
			m.copyBuf = nil
			m.err = err
			m.arrive(false)
			return
		}
		m.copyBuf = nb
		m.copyFirst = true
	}
	if m.w.copyVer >= 2 {
		_ = m.w.c.Request(m.copyFrame, 2, wayland.Object(m.copyBuf.ID)) // copy_with_damage
	} else {
		_ = m.w.c.Request(m.copyFrame, 0, wayland.Object(m.copyBuf.ID)) // copy
	}
}

// pickFormat chooses the shm format to capture in, from those offered.
func pickFormat(formats []uint32) (uint32, bool) {
	best, ok := uint32(0), false
	for _, f := range formats {
		switch f {
		case wayland.FormatXRGB8888, wayland.FormatARGB8888, wayland.FormatXBGR8888, wayland.FormatABGR8888:
			if !ok || preferFormat(f, best) {
				best, ok = f, true
			}
		}
	}
	return best, ok
}

// preferFormat: XRGB and ARGB are 0x00RRGGBB as they are; the BGR ones need
// a swap.
func preferFormat(a, b uint32) bool {
	rank := func(f uint32) int {
		switch f {
		case wayland.FormatXRGB8888:
			return 0
		case wayland.FormatARGB8888:
			return 1
		case wayland.FormatXBGR8888:
			return 2
		}
		return 3
	}
	return rank(a) < rank(b)
}

// convert copies rectangle r of shm buffer b into img (width iw) as
// 0x00RRGGBB, flipping it if yInvert. It returns r clipped to the buffer.
func convert(img []uint32, iw int, b *wayland.ShmBuffer, r image.Rectangle, yInvert bool) image.Rectangle {
	r = r.Intersect(image.Rect(0, 0, min(b.Width, iw), b.Height))
	if r.Empty() {
		return r
	}
	swap := b.Format == wayland.FormatXBGR8888 || b.Format == wayland.FormatABGR8888
	for y := r.Min.Y; y < r.Max.Y; y++ {
		sy := y
		if yInvert {
			sy = b.Height - 1 - y
		}
		src := b.Data[sy*b.Stride+r.Min.X*4 : sy*b.Stride+r.Max.X*4]
		dst := img[y*iw+r.Min.X : y*iw+r.Max.X]
		for i := range dst {
			p := uint32(src[i*4]) | uint32(src[i*4+1])<<8 | uint32(src[i*4+2])<<16
			if swap {
				p = p>>16&0xff | p&0xff00 | (p&0xff)<<16
			}
			dst[i] = p
		}
	}
	return r
}

// convertARGB reads a whole buffer as ARGB with its alpha.
func convertARGB(dst []uint32, b *wayland.ShmBuffer) {
	swap := b.Format == wayland.FormatXBGR8888 || b.Format == wayland.FormatABGR8888
	alpha := b.Format == wayland.FormatARGB8888 || b.Format == wayland.FormatABGR8888
	for y := 0; y < b.Height; y++ {
		row := b.Data[y*b.Stride:]
		for x := 0; x < b.Width; x++ {
			s := row[x*4:]
			p := uint32(s[0]) | uint32(s[1])<<8 | uint32(s[2])<<16
			if swap {
				p = p>>16&0xff | p&0xff00 | (p&0xff)<<16
			}
			a := uint32(0xff)
			if alpha {
				a = uint32(s[3])
			}
			dst[y*b.Width+x] = a<<24 | p
		}
	}
}
