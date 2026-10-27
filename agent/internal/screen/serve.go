package screen

import (
	"bufio"
	"bytes"
	"compress/zlib"
	"encoding/binary"
	"errors"
	"image"
	"io"
	"sync"
	"sync/atomic"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/protocol"
)

// Capturer reads a screen. Serve calls Size, Capture and the optional
// interfaces from one goroutine, and Cursor from another: Cursor must be safe
// to call while the others run.
type Capturer interface {
	// Size is the screen's current size.
	Size() (w, h int, err error)
	// Capture reads the whole w×h screen into dst as 0x00RRGGBB pixels.
	Capture(dst []uint32, w, h int) error
	// Cursor reports the pointer: where its hot spot is and, when its image
	// may have changed (a new Serial), the image.
	Cursor() (Cursor, error)
}

// Cursor is the pointer as a Capturer sees it.
type Cursor struct {
	X, Y    int
	Visible bool
	// Serial changes whenever the image changes; Pixels (ARGB, straight
	// alpha) are only read when it does.
	Serial        uint32
	Width, Height int
	HotX, HotY    int
	Pixels        []uint32
}

// Damager is a Capturer that knows what changed: after each Capture, Damage
// gives the rectangles (in the captured image) that may differ from the
// previous Capture, so Serve compares only the tiles under them. ok false
// means it does not know this time (compare everything); a full frame
// compares everything anyway.
type Damager interface {
	Damage() (rects []image.Rectangle, ok bool)
}

// Blocker is a Capturer that can lose the screen for a while: Windows while
// the lock screen, UAC or Ctrl+Alt+Del is up (ADR-0031 §4). While Blocked,
// Serve neither captures nor sends the cursor, and tells the client with
// INPUT_READY (blocked set, then clear again), as the mouse pad does.
type Blocker interface {
	Blocked() bool
}

// InputHandler applies the client's input frames (an *inject.Handler).
type InputHandler interface {
	Handle(protocol.Frame) error
	Release()
}

// Options tune a session; the zero value takes the defaults.
type Options struct {
	// Interval is the shortest time between two captures (about 30 per second).
	Interval time.Duration
	// IdleInterval is the time between captures once nothing changes.
	IdleInterval time.Duration
	// RefineAfter is how long the screen must stay still before the tiles
	// sent as JPEG are sent again lossless.
	RefineAfter time.Duration
	// MaxInFlight is how many frames may wait for their acknowledgement.
	MaxInFlight int
	// CursorInterval is how often the cursor is read while the client is
	// moving it: input wakes the loop at once, and the cursor is read again
	// this often until ActiveFor after the last input, so it follows the
	// pointer at the client's pace rather than the capture's.
	CursorInterval time.Duration
	// ActiveFor is how long after the client's last input the cursor is read
	// every CursorInterval and the screen captured every ActiveInterval.
	ActiveFor time.Duration
	// ActiveInterval is the time between captures while the client is moving
	// the pointer, for a Capturer that knows what changed (a Damager), whose
	// captures cost little: where the cursor is drawn into the image (GNOME),
	// it is what makes it follow the pointer. Others keep Interval.
	ActiveInterval time.Duration
	// HelloTimeout bounds the wait for SCREEN_HELLO.
	HelloTimeout time.Duration
}

func (o Options) withDefaults() Options {
	if o.Interval <= 0 {
		o.Interval = 33 * time.Millisecond
	}
	if o.IdleInterval <= 0 {
		o.IdleInterval = 100 * time.Millisecond
	}
	if o.RefineAfter <= 0 {
		o.RefineAfter = 400 * time.Millisecond
	}
	if o.MaxInFlight <= 0 {
		o.MaxInFlight = 2
	}
	if o.CursorInterval <= 0 {
		o.CursorInterval = 8 * time.Millisecond
	}
	if o.ActiveFor <= 0 {
		o.ActiveFor = 500 * time.Millisecond
	}
	if o.ActiveInterval <= 0 {
		o.ActiveInterval = 16 * time.Millisecond
	}
	if o.HelloTimeout <= 0 {
		o.HelloTimeout = 15 * time.Second
	}
	return o
}

// ErrNoHello reports a client that never sent SCREEN_HELLO.
var ErrNoHello = errors.New("screen: the client sent no SCREEN_HELLO")

// Auto quality bounds and starting point.
const (
	autoStart = 75
	autoMin   = 30
	autoMax   = 85
)

// session is the state the reader goroutine shares with the sender.
type session struct {
	mu      sync.Mutex
	quality int // protocol quality: 0 lossless, 1..100, QualityAuto
	hello   bool
	acked   uint32
	sentAt  map[uint32]time.Time
	auto    int // the JPEG quality AUTO uses now
	wake    chan struct{}
	// The client sent input: one for the frames, one for the cursor.
	input, inputCursor chan struct{}
	inputAt            time.Time
	done               chan struct{}
	readErr            error
}

func (s *session) signal() { poke(s.wake) }

func poke(c chan struct{}) {
	select {
	case c <- struct{}{}:
	default:
	}
}

// Serve runs one remote desktop connection until the client goes away: it
// waits for SCREEN_HELLO, sends SCREEN_SIZE and a whole frame, then the tiles
// that change, never more than MaxInFlight frames ahead of the client's
// acknowledgements, and the cursor whenever it moves or changes. Input frames
// go to input (if not nil), which releases what was left pressed at the end.
//
// The cursor has a goroutine of its own, so it follows the pointer however
// long a capture or its encoding takes: Cursor may be called while Capture
// runs.
func Serve(conn io.ReadWriter, cap Capturer, input InputHandler, opt Options) error {
	opt = opt.withDefaults()
	s := &session{
		sentAt: map[uint32]time.Time{}, auto: autoStart, wake: make(chan struct{}, 1),
		input: make(chan struct{}, 1), inputCursor: make(chan struct{}, 1), done: make(chan struct{}),
	}
	if input != nil {
		defer input.Release()
	}
	go s.read(conn, input)

	select {
	case <-s.wake:
	case <-s.done:
		return s.readErr
	case <-time.After(opt.HelloTimeout):
		return ErrNoHello
	}

	out := &writer{w: bufio.NewWriterSize(conn, 64*1024)}
	var blocked atomic.Bool
	stop := make(chan struct{})
	cursorDone := make(chan struct{})
	var cursorErr error
	go func() {
		defer close(cursorDone)
		cursorErr = s.serveCursor(cap, out, &blocked, opt, stop)
	}()
	defer func() {
		close(stop)
		<-cursorDone
	}()

	err := s.serveFrames(cap, out, &blocked, opt, cursorDone)
	if errors.Is(err, errCursorEnded) {
		return cursorErr
	}
	return err
}

// errCursorEnded ends serveFrames when the cursor's goroutine ended first.
var errCursorEnded = errors.New("screen: the cursor ended")

// writer is the connection's buffered writer, shared by the frames and the
// cursor: each frame goes out whole.
type writer struct {
	mu sync.Mutex
	w  *bufio.Writer
}

func (o *writer) emit(f protocol.Frame) error {
	o.mu.Lock()
	defer o.mu.Unlock()
	_, err := o.w.Write(protocol.Encode(f))
	return err
}

func (o *writer) flush() error {
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.w.Flush()
}

// serveCursor sends the cursor whenever it moves or changes, credit or not:
// it is what makes the session feel live, and it is tiny. It reads it every
// CursorInterval while the client is moving it (and at once on its input),
// every Interval otherwise, and not while the screen is blocked.
func (s *session) serveCursor(cap Capturer, out *writer, blocked *atomic.Bool, opt Options, stop <-chan struct{}) error {
	var (
		cursor Cursor
		sent   bool
	)
	for {
		if blocked.Load() {
			// Sent again whole once the screen is back.
			sent = false
		} else if c, err := cap.Cursor(); err == nil {
			if !sent || c.Serial != cursor.Serial {
				if len(c.Pixels) > 0 {
					if err := out.emit(cursorShape(c)); err != nil {
						return err
					}
				}
			}
			if !sent || c.X != cursor.X || c.Y != cursor.Y || c.Visible != cursor.Visible || c.Serial != cursor.Serial {
				if err := out.emit(protocol.Frame{Type: protocol.TypeCursorPos, X: c.X, Y: c.Y, Visible: c.Visible}); err != nil {
					return err
				}
				if err := out.flush(); err != nil {
					return err
				}
			}
			cursor, sent = c, true
		}

		wait := opt.Interval
		if s.active(opt) {
			wait = opt.CursorInterval
		}
		timer := time.NewTimer(wait)
		select {
		case <-stop:
			timer.Stop()
			return nil
		case <-s.done:
			timer.Stop()
			return nil
		case <-s.inputCursor:
			timer.Stop()
		case <-timer.C:
		}
	}
}

// active reports whether the client sent input lately.
func (s *session) active(opt Options) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return time.Since(s.inputAt) < opt.ActiveFor
}

// serveFrames captures and sends the frames as credit allows, until the
// client goes away or the cursor's goroutine ends (cursorDone closes).
func (s *session) serveFrames(cap Capturer, out *writer, blocked *atomic.Bool, opt Options, cursorDone <-chan struct{}) error {
	var (
		enc        *Encoder
		pixels     []uint32
		sw, sh     int
		seq        uint32
		lastChange = time.Now()
		interval   = opt.Interval
		// When the screen is captured next, and was last.
		nextCapture, lastCapture time.Time
	)
	blocker, _ := cap.(Blocker)
	damager, _ := cap.(Damager)
	activeInterval := opt.Interval
	if damager != nil {
		activeInterval = min(opt.ActiveInterval, opt.Interval)
	}
	for {
		select {
		case <-s.done:
			return nil
		case <-cursorDone:
			return errCursorEnded
		default:
		}

		if blocker != nil {
			if now := blocker.Blocked(); now != blocked.Load() {
				blocked.Store(now)
				if err := out.emit(protocol.Frame{Type: protocol.TypeInputReady, Blocked: now}); err != nil {
					return err
				}
				if err := out.flush(); err != nil {
					return err
				}
			}
			if blocked.Load() {
				timer := time.NewTimer(opt.IdleInterval)
				select {
				case <-s.done:
					timer.Stop()
					return nil
				case <-cursorDone:
					timer.Stop()
					return errCursorEnded
				case <-timer.C:
				}
				continue
			}
		}

		s.mu.Lock()
		credit := seq-s.acked < uint32(opt.MaxInFlight)
		quality := s.quality
		if quality == protocol.QualityAuto {
			quality = s.auto
		}
		active := time.Since(s.inputAt) < opt.ActiveFor
		s.mu.Unlock()

		if active && interval != activeInterval {
			// Input tends to change the screen (hover, menus, the cursor
			// itself where it is in the image): capture more often.
			interval = activeInterval
			if next := lastCapture.Add(interval); next.Before(nextCapture) {
				nextCapture = next
			}
		}
		if credit && !time.Now().Before(nextCapture) {
			lastCapture = time.Now()
			nw, nh, err := cap.Size()
			if err != nil {
				return err
			}
			full := false
			if nw != sw || nh != sh || enc == nil {
				sw, sh = nw, nh
				enc = NewEncoder(sw, sh)
				pixels = make([]uint32, sw*sh)
				full = true
				if err := out.emit(protocol.Frame{Type: protocol.TypeScreenSize, Width: sw, Height: sh}); err != nil {
					return err
				}
			}
			if err := cap.Capture(pixels, sw, sh); err != nil {
				return err
			}
			if damager != nil {
				// Read it every time, so it starts over from this capture.
				if rects, ok := damager.Damage(); ok && !full {
					enc.Hint(rects)
				}
			}
			n, err := enc.Encode(pixels, full, quality, out.emit)
			if err != nil {
				return err
			}
			if active {
				interval = activeInterval
			} else if n > 0 || interval < opt.Interval {
				interval = opt.Interval
			}
			if n > 0 {
				lastChange = time.Now()
			} else if time.Since(lastChange) >= opt.RefineAfter && enc.Lossy() {
				if n, err = enc.Refine(out.emit); err != nil {
					return err
				}
			} else if time.Since(lastChange) >= opt.RefineAfter && !active {
				interval = opt.IdleInterval
			}
			nextCapture = lastCapture.Add(interval)
			if n > 0 {
				seq++
				s.mu.Lock()
				s.sentAt[seq] = time.Now()
				s.mu.Unlock()
				if err := out.emit(protocol.Frame{Type: protocol.TypeScreenFrameEnd, Seq: seq}); err != nil {
					return err
				}
			}
			if err := out.flush(); err != nil {
				return err
			}
		}

		// Sleep until the next capture. Without credit, an acknowledgement
		// ends the wait early; input always does, in case it ends idling.
		var acks <-chan struct{}
		wait := interval
		if credit {
			wait = time.Until(nextCapture)
		} else {
			acks = s.wake
		}
		timer := time.NewTimer(max(wait, 0))
		select {
		case <-s.done:
			timer.Stop()
			return nil
		case <-cursorDone:
			timer.Stop()
			return errCursorEnded
		case <-acks:
			timer.Stop()
		case <-s.input:
			timer.Stop()
		case <-timer.C:
		}
	}
}

// read handles the client's frames until the connection ends.
func (s *session) read(conn io.Reader, input InputHandler) {
	defer close(s.done)
	dec := &protocol.Decoder{Max: protocol.MaxInputPayload}
	buf := make([]byte, 16*1024)
	for {
		n, err := conn.Read(buf)
		if n > 0 {
			frames, derr := dec.Feed(buf[:n])
			for _, f := range frames {
				s.handle(f, input)
			}
			if derr != nil {
				s.readErr = derr
				return
			}
		}
		if err != nil {
			if !errors.Is(err, io.EOF) {
				s.readErr = err
			}
			return
		}
	}
}

func (s *session) handle(f protocol.Frame, input InputHandler) {
	switch f.Type {
	case protocol.TypeScreenHello:
		s.mu.Lock()
		s.quality = int(f.Quality)
		first := !s.hello
		s.hello = true
		s.mu.Unlock()
		if first {
			s.signal()
		}
	case protocol.TypeScreenAck:
		s.mu.Lock()
		if f.Seq > s.acked {
			if at, ok := s.sentAt[f.Seq]; ok {
				s.adapt(time.Since(at))
			}
			for k := range s.sentAt {
				if k <= f.Seq {
					delete(s.sentAt, k)
				}
			}
			s.acked = f.Seq
		}
		s.mu.Unlock()
		s.signal()
	default:
		if input != nil {
			_ = input.Handle(f)
		}
		s.mu.Lock()
		s.inputAt = time.Now()
		s.mu.Unlock()
		poke(s.input)
		poke(s.inputCursor)
	}
}

// adapt moves the AUTO quality with how long a frame took to be shown: down
// on a slow link, back up once frames come back quickly. Called under mu.
func (s *session) adapt(rtt time.Duration) {
	switch {
	case rtt > 400*time.Millisecond:
		s.auto = max(autoMin, s.auto-10)
	case rtt < 150*time.Millisecond:
		s.auto = min(autoMax, s.auto+2)
	}
}

// cursorShape encodes c's image as a CURSOR_SHAPE frame.
func cursorShape(c Cursor) protocol.Frame {
	raw := make([]byte, len(c.Pixels)*4)
	for i, p := range c.Pixels {
		binary.BigEndian.PutUint32(raw[i*4:], p)
	}
	var b bytes.Buffer
	zw := zlib.NewWriter(&b)
	_, _ = zw.Write(raw)
	_ = zw.Close()
	return protocol.Frame{Type: protocol.TypeCursorShape, Width: c.Width, Height: c.Height, X: c.HotX, Y: c.HotY, Bytes: b.Bytes()}
}
