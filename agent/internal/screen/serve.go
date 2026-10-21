package screen

import (
	"bufio"
	"bytes"
	"compress/zlib"
	"encoding/binary"
	"errors"
	"io"
	"sync"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/protocol"
)

// Capturer reads a screen. Implementations need not be safe for concurrent
// use: Serve calls them from one goroutine.
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
	done    chan struct{}
	readErr error
}

func (s *session) signal() {
	select {
	case s.wake <- struct{}{}:
	default:
	}
}

// Serve runs one remote desktop connection until the client goes away: it
// waits for SCREEN_HELLO, sends SCREEN_SIZE and a whole frame, then the tiles
// that change, never more than MaxInFlight frames ahead of the client's
// acknowledgements, and the cursor whenever it moves or changes. Input frames
// go to input (if not nil), which releases what was left pressed at the end.
func Serve(conn io.ReadWriter, cap Capturer, input InputHandler, opt Options) error {
	opt = opt.withDefaults()
	s := &session{sentAt: map[uint32]time.Time{}, auto: autoStart, wake: make(chan struct{}, 1), done: make(chan struct{})}
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

	w := bufio.NewWriterSize(conn, 64*1024)
	emit := func(f protocol.Frame) error {
		_, err := w.Write(protocol.Encode(f))
		return err
	}
	var (
		enc        *Encoder
		pixels     []uint32
		sw, sh     int
		seq        uint32
		cursor     Cursor
		cursorSent bool
		lastChange = time.Now()
		interval   = opt.Interval
	)
	for {
		select {
		case <-s.done:
			return nil
		default:
		}

		// The cursor goes out whenever it changes, credit or not: it is what
		// makes the session feel live, and it is tiny.
		if c, err := cap.Cursor(); err == nil {
			if !cursorSent || c.Serial != cursor.Serial {
				if len(c.Pixels) > 0 {
					if err := emit(cursorShape(c)); err != nil {
						return err
					}
				}
			}
			if !cursorSent || c.X != cursor.X || c.Y != cursor.Y || c.Visible != cursor.Visible || c.Serial != cursor.Serial {
				if err := emit(protocol.Frame{Type: protocol.TypeCursorPos, X: c.X, Y: c.Y, Visible: c.Visible}); err != nil {
					return err
				}
			}
			cursor, cursorSent = c, true
		}

		s.mu.Lock()
		credit := seq-s.acked < uint32(opt.MaxInFlight)
		quality := s.quality
		if quality == protocol.QualityAuto {
			quality = s.auto
		}
		s.mu.Unlock()

		if credit {
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
				if err := emit(protocol.Frame{Type: protocol.TypeScreenSize, Width: sw, Height: sh}); err != nil {
					return err
				}
			}
			if err := cap.Capture(pixels, sw, sh); err != nil {
				return err
			}
			n, err := enc.Encode(pixels, full, quality, emit)
			if err != nil {
				return err
			}
			if n > 0 {
				lastChange = time.Now()
				interval = opt.Interval
			} else if time.Since(lastChange) >= opt.RefineAfter && enc.Lossy() {
				if n, err = enc.Refine(emit); err != nil {
					return err
				}
			} else if time.Since(lastChange) >= opt.RefineAfter {
				interval = opt.IdleInterval
			}
			if n > 0 {
				seq++
				s.mu.Lock()
				s.sentAt[seq] = time.Now()
				s.mu.Unlock()
				if err := emit(protocol.Frame{Type: protocol.TypeScreenFrameEnd, Seq: seq}); err != nil {
					return err
				}
			}
		}
		if err := w.Flush(); err != nil {
			return err
		}

		// Without credit, an acknowledgement ends the wait early.
		var acks <-chan struct{}
		if !credit {
			acks = s.wake
		}
		timer := time.NewTimer(interval)
		select {
		case <-s.done:
			timer.Stop()
			return nil
		case <-acks:
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
