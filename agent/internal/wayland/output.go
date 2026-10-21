//go:build linux

package wayland

import (
	"image"
	"sort"
	"sync"
)

// Output is a monitor as the compositor describes it.
type Output struct {
	// Global is its registry name; ID its wl_output object.
	Global uint32
	ID     uint32
	Name   string
	// Width and Height are its current mode, in physical pixels.
	Width, Height int
	// Scale is the integer scale of wl_output; Transform its rotation
	// (wl_output.transform).
	Scale     int
	Transform int
	// Logical is where it sits in the compositor's layout and how big it is
	// there, in logical pixels: from xdg_output when the compositor has it,
	// else from the geometry, the mode and the scale.
	Logical image.Rectangle
}

// outputState is an Output being described; pending fills until done.
type outputState struct {
	cur, pending Output
	geomX, geomY int
	logical      image.Rectangle
	hasLogical   bool
	ready        bool
	xdg          uint32
}

// Outputs keeps the compositor's outputs up to date, as they are plugged,
// unplugged and changed.
type Outputs struct {
	c        *Conn
	mu       sync.Mutex
	outs     map[uint32]*outputState // by global name
	xdgMgr   uint32
	gen      uint64
	onChange func()
}

// WatchOutputs binds every output the compositor has, and those it adds
// later. onChange (if not nil) runs, on the reading goroutine, whenever the
// list or an output changes.
func WatchOutputs(c *Conn, onChange func()) (*Outputs, error) {
	o := &Outputs{c: c, outs: map[uint32]*outputState{}, onChange: onChange}
	if g, ok := c.Find("zxdg_output_manager_v1"); ok {
		id, _, err := c.Bind(g, 3, nil)
		if err != nil {
			return nil, err
		}
		o.xdgMgr = id
	}
	c.WatchGlobals(func(g Global, added bool) {
		if g.Interface != "wl_output" {
			return
		}
		if added {
			_ = o.add(g)
			return
		}
		o.mu.Lock()
		st, ok := o.outs[g.Name]
		delete(o.outs, g.Name)
		o.gen++
		o.mu.Unlock()
		if ok {
			c.Forget(st.cur.ID)
			if st.xdg != 0 {
				c.Forget(st.xdg)
			}
		}
		o.changed()
	})
	for _, g := range c.Globals() {
		if g.Interface == "wl_output" {
			if err := o.add(g); err != nil {
				return nil, err
			}
		}
	}
	return o, c.Roundtrip()
}

func (o *Outputs) changed() {
	if o.onChange != nil {
		o.onChange()
	}
}

func (o *Outputs) add(g Global) error {
	st := &outputState{}
	st.pending.Global, st.pending.Scale = g.Name, 1
	version := min(4, g.Version)
	id, _, err := o.c.Bind(g, version, func(op uint16, m *Message) { o.outputEvent(st, version, op, m) })
	if err != nil {
		return err
	}
	o.mu.Lock()
	st.pending.ID = id
	o.outs[g.Name] = st
	o.mu.Unlock()
	if o.xdgMgr != 0 {
		st.xdg = o.c.NewID(func(op uint16, m *Message) { o.xdgEvent(st, op, m) })
		return o.c.Request(o.xdgMgr, 1, NewID(st.xdg), Object(id)) // get_xdg_output
	}
	return nil
}

func (o *Outputs) outputEvent(st *outputState, version uint32, op uint16, m *Message) {
	o.mu.Lock()
	switch op {
	case 0: // geometry
		st.geomX, st.geomY = int(m.Int()), int(m.Int())
		m.Int()
		m.Int()
		m.Int()
		_, _ = m.String(), m.String() // make, model
		st.pending.Transform = int(m.Int())
	case 1: // mode
		flags := m.Uint()
		w, h := int(m.Int()), int(m.Int())
		if flags&1 != 0 { // current
			st.pending.Width, st.pending.Height = w, h
		}
	case 2: // done
		o.commit(st)
	case 3: // scale
		st.pending.Scale = max(1, int(m.Int()))
	case 4: // name
		st.pending.Name = m.String()
	}
	if version < 2 && op <= 1 {
		o.commit(st) // no done before version 2
	}
	ready := st.ready
	o.mu.Unlock()
	if op == 2 && ready {
		o.changed()
	}
}

func (o *Outputs) xdgEvent(st *outputState, op uint16, m *Message) {
	o.mu.Lock()
	defer o.mu.Unlock()
	switch op {
	case 0: // logical_position
		x, y := int(m.Int()), int(m.Int())
		st.logical = image.Rect(x, y, x+st.logical.Dx(), y+st.logical.Dy())
		st.hasLogical = true
	case 1: // logical_size
		w, h := int(m.Int()), int(m.Int())
		st.logical.Max = st.logical.Min.Add(image.Pt(w, h))
		st.hasLogical = true
	case 3: // name
		if st.pending.Name == "" {
			st.pending.Name = m.String()
		}
	}
}

// commit makes the pending description current. Called under mu.
func (o *Outputs) commit(st *outputState) {
	p := st.pending
	if st.hasLogical {
		p.Logical = st.logical
	} else {
		w, h := p.Width/p.Scale, p.Height/p.Scale
		if p.Transform&1 == 1 { // 90 or 270 degrees
			w, h = h, w
		}
		p.Logical = image.Rect(st.geomX, st.geomY, st.geomX+w, st.geomY+h)
	}
	st.cur = p
	st.ready = p.Width > 0 && p.Height > 0
	o.gen++
}

// List is the outputs described so far, left to right and top to bottom.
// Generation changes whenever the list or an output does.
func (o *Outputs) List() ([]Output, uint64) {
	o.mu.Lock()
	defer o.mu.Unlock()
	var out []Output
	for _, st := range o.outs {
		if st.ready {
			out = append(out, st.cur)
		}
	}
	sort.Slice(out, func(i, j int) bool {
		a, b := out[i].Logical.Min, out[j].Logical.Min
		if a.X != b.X {
			return a.X < b.X
		}
		if a.Y != b.Y {
			return a.Y < b.Y
		}
		return out[i].Global < out[j].Global
	})
	return out, o.gen
}

// Shm formats the capture uses.
const (
	FormatARGB8888 = 0
	FormatXRGB8888 = 1
	FormatXBGR8888 = 0x34324258
	FormatABGR8888 = 0x34324241
)

// ShmBuffer is a wl_buffer in shared memory.
type ShmBuffer struct {
	ID                    uint32
	Width, Height, Stride int
	Format                uint32
	Data                  []byte
	mem                   *Memfd
	c                     *Conn
}

// NewShmBuffer creates a w×h buffer of format through the wl_shm object shm.
func NewShmBuffer(c *Conn, shm uint32, w, h, stride int, format uint32) (*ShmBuffer, error) {
	size := stride * h
	mem, err := NewMemfd("titan-agent-screen", size)
	if err != nil {
		return nil, err
	}
	pool := c.NewID(nil)
	if err := c.Request(shm, 0, NewID(pool), Fd(mem.Fd), int32(size)); err != nil { // create_pool
		mem.Close()
		return nil, err
	}
	b := &ShmBuffer{Width: w, Height: h, Stride: stride, Format: format, Data: mem.Data, mem: mem, c: c}
	b.ID = c.NewID(nil)
	err = c.Request(pool, 0, NewID(b.ID), int32(0), int32(w), int32(h), int32(stride), format) // create_buffer
	_ = c.Request(pool, 1)                                                                     // destroy
	c.Forget(pool)
	if err != nil {
		mem.Close()
		return nil, err
	}
	return b, nil
}

// Destroy releases the buffer.
func (b *ShmBuffer) Destroy() {
	_ = b.c.Request(b.ID, 0) // destroy
	b.c.Forget(b.ID)
	b.mem.Close()
}
