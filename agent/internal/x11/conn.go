// Package x11 is a minimal X11 client (ADR-0029): just what the remote
// desktop and the mouse pad need from a user's X server — connect with the
// user's MIT-MAGIC-COOKIE-1, read the root window's pixels (GetImage), the
// cursor (XFIXES) and the pointer, and inject input (XTEST) with the keyboard
// mapping. It speaks the wire protocol directly over the server's socket, so
// the agent keeps golang.org/x/sys as its only dependency (ADR-0009 §4).
//
// A Conn is synchronous: one request at a time waits for its reply. Requests
// without a reply (XTEST, ChangeKeyboardMapping) are checked lazily: an error
// they cause is returned by the next request that waits for a reply. The
// client selects no events; the few the server sends anyway are skipped.
package x11

import (
	"bufio"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"time"
)

// le is the byte order this client asks the server to use.
var le = binary.LittleEndian

// Setup is what the server says about itself when the connection opens.
type Setup struct {
	Vendor            string
	MaxRequestLen     int // in 4-byte units
	ImageByteOrder    uint8
	MinKeycode        uint8
	MaxKeycode        uint8
	Formats           []Format
	Root              uint32 // the first screen's root window
	RootDepth         uint8
	RootVisual        uint32
	Width, Height     uint16 // of the first screen, at connection time
	RedMask           uint32 // of the root visual
	GreenMask         uint32
	BlueMask          uint32
	ResourceIDBase    uint32
	ResourceIDMask    uint32
	ProtocolMajor     uint16
	ProtocolMinor     uint16
	ReleaseNumber     uint32
	BitmapScanlinePad uint8
}

// Format is one pixmap format the server supports.
type Format struct {
	Depth, BitsPerPixel, ScanlinePad uint8
}

// Error is an X protocol error the server sent for a request.
type Error struct {
	Code     uint8
	Major    uint8
	Minor    uint16
	BadValue uint32
}

func (e *Error) Error() string {
	return fmt.Sprintf("x11: error %d (%s) on request %d.%d, value %#x", e.Code, errorName(e.Code), e.Major, e.Minor, e.BadValue)
}

func errorName(code uint8) string {
	names := map[uint8]string{1: "Request", 2: "Value", 3: "Window", 4: "Pixmap", 5: "Atom", 6: "Cursor",
		7: "Font", 8: "Match", 9: "Drawable", 10: "Access", 11: "Alloc", 12: "Colormap", 13: "GContext",
		14: "IDChoice", 15: "Name", 16: "Length", 17: "Implementation"}
	if n, ok := names[code]; ok {
		return n
	}
	return "extension"
}

// ErrClosed is returned once the connection is closed or broken.
var ErrClosed = errors.New("x11: connection closed")

// Conn is a connection to an X server.
type Conn struct {
	Setup Setup

	mu      sync.Mutex
	c       net.Conn
	r       *bufio.Reader
	seq     uint16 // sequence number of the last request sent
	pending error  // an error a reply-less request caused
	broken  error
	exts    map[string]Extension
}

// Extension is a server extension as QueryExtension reports it.
type Extension struct {
	Present    bool
	Opcode     uint8
	FirstEvent uint8
	FirstError uint8
}

// NewConn performs the connection setup on c with the given authorization
// (name and data; both empty for none) and returns the open connection.
func NewConn(c net.Conn, authName string, authData []byte) (*Conn, error) {
	x := &Conn{c: c, r: bufio.NewReaderSize(c, 64*1024), exts: map[string]Extension{}}
	if err := x.handshake(authName, authData); err != nil {
		c.Close()
		return nil, err
	}
	return x, nil
}

func pad4(n int) int { return (4 - n%4) % 4 }

func (x *Conn) handshake(authName string, authData []byte) error {
	n, d := len(authName), len(authData)
	req := make([]byte, 12+n+pad4(n)+d+pad4(d))
	req[0] = 'l' // little-endian
	le.PutUint16(req[2:], 11)
	le.PutUint16(req[4:], 0)
	le.PutUint16(req[6:], uint16(n))
	le.PutUint16(req[8:], uint16(d))
	copy(req[12:], authName)
	copy(req[12+n+pad4(n):], authData)
	_ = x.c.SetDeadline(time.Now().Add(10 * time.Second))
	defer x.c.SetDeadline(time.Time{})
	if _, err := x.c.Write(req); err != nil {
		return err
	}
	head := make([]byte, 8)
	if _, err := io.ReadFull(x.r, head); err != nil {
		return fmt.Errorf("x11: no setup reply: %w", err)
	}
	extra := make([]byte, int(le.Uint16(head[6:]))*4)
	if _, err := io.ReadFull(x.r, extra); err != nil {
		return fmt.Errorf("x11: short setup reply: %w", err)
	}
	switch head[0] {
	case 0:
		reason := extra
		if l := int(head[1]); l <= len(reason) {
			reason = reason[:l]
		}
		return fmt.Errorf("x11: the server refused the connection: %s", trimReason(reason))
	case 2:
		return fmt.Errorf("x11: the server asks for more authentication: %s", trimReason(extra))
	case 1:
	default:
		return fmt.Errorf("x11: unexpected setup status %d", head[0])
	}
	s, err := parseSetup(extra)
	if err != nil {
		return err
	}
	s.ProtocolMajor, s.ProtocolMinor = le.Uint16(head[2:]), le.Uint16(head[4:])
	x.Setup = s
	return nil
}

func trimReason(b []byte) string {
	for len(b) > 0 && (b[len(b)-1] == 0 || b[len(b)-1] == '\n') {
		b = b[:len(b)-1]
	}
	return string(b)
}

// parseSetup reads the body of a successful setup reply (after its 8 bytes).
func parseSetup(b []byte) (Setup, error) {
	var s Setup
	short := errors.New("x11: malformed setup reply")
	if len(b) < 32 {
		return s, short
	}
	s.ReleaseNumber = le.Uint32(b[0:])
	s.ResourceIDBase = le.Uint32(b[4:])
	s.ResourceIDMask = le.Uint32(b[8:])
	vendorLen := int(le.Uint16(b[16:]))
	s.MaxRequestLen = int(le.Uint16(b[18:]))
	screens := int(b[20])
	formats := int(b[21])
	s.ImageByteOrder = b[22]
	s.BitmapScanlinePad = b[25]
	s.MinKeycode, s.MaxKeycode = b[26], b[27]
	p := 32
	if len(b) < p+vendorLen {
		return s, short
	}
	s.Vendor = string(b[p : p+vendorLen])
	p += vendorLen + pad4(vendorLen)
	for i := 0; i < formats; i++ {
		if len(b) < p+8 {
			return s, short
		}
		s.Formats = append(s.Formats, Format{Depth: b[p], BitsPerPixel: b[p+1], ScanlinePad: b[p+2]})
		p += 8
	}
	if screens < 1 || len(b) < p+40 {
		return s, errors.New("x11: the server has no screen")
	}
	s.Root = le.Uint32(b[p:])
	s.Width, s.Height = le.Uint16(b[p+20:]), le.Uint16(b[p+22:])
	s.RootVisual = le.Uint32(b[p+32:])
	s.RootDepth = b[p+38]
	depths := int(b[p+39])
	p += 40
	for i := 0; i < depths; i++ {
		if len(b) < p+8 {
			return s, short
		}
		visuals := int(le.Uint16(b[p+2:]))
		p += 8
		for j := 0; j < visuals; j++ {
			if len(b) < p+24 {
				return s, short
			}
			if le.Uint32(b[p:]) == s.RootVisual {
				s.RedMask, s.GreenMask, s.BlueMask = le.Uint32(b[p+8:]), le.Uint32(b[p+12:]), le.Uint32(b[p+16:])
			}
			p += 24
		}
	}
	return s, nil
}

// Close closes the connection.
func (x *Conn) Close() error {
	x.mu.Lock()
	defer x.mu.Unlock()
	if x.broken == nil {
		x.broken = ErrClosed
	}
	return x.c.Close()
}

// send writes one request (whose length field it fills in) and returns its
// sequence number. The caller holds mu.
func (x *Conn) send(req []byte) (uint16, error) {
	if x.broken != nil {
		return 0, x.broken
	}
	if len(req)%4 != 0 {
		panic("x11: request not padded")
	}
	le.PutUint16(req[2:], uint16(len(req)/4))
	if _, err := x.c.Write(req); err != nil {
		x.broken = err
		return 0, err
	}
	x.seq++
	return x.seq, nil
}

// readReply reads until the reply or error for seq. Events are skipped; an
// error for an earlier request (one with no reply) is kept for later. The
// caller holds mu.
func (x *Conn) readReply(seq uint16) ([]byte, error) {
	head := make([]byte, 32)
	for {
		if _, err := io.ReadFull(x.r, head); err != nil {
			x.broken = err
			return nil, err
		}
		switch head[0] {
		case 0: // error
			e := &Error{Code: head[1], BadValue: le.Uint32(head[4:]), Minor: le.Uint16(head[8:]), Major: head[10]}
			if le.Uint16(head[2:]) == seq {
				return nil, e
			}
			if x.pending == nil {
				x.pending = e
			}
		case 1: // reply
			extra := int(le.Uint32(head[4:])) * 4
			reply := make([]byte, 32+extra)
			copy(reply, head)
			if _, err := io.ReadFull(x.r, reply[32:]); err != nil {
				x.broken = err
				return nil, err
			}
			if le.Uint16(head[2:]) == seq {
				return reply, nil
			}
		default: // an event; a GenericEvent (35) carries more
			if head[0]&0x7f == 35 {
				extra := int(le.Uint32(head[4:])) * 4
				if _, err := x.r.Discard(extra); err != nil {
					x.broken = err
					return nil, err
				}
			}
		}
	}
}

// roundTrip sends req and waits for its reply. A pending error from an
// earlier reply-less request is returned first.
func (x *Conn) roundTrip(req []byte) ([]byte, error) {
	x.mu.Lock()
	defer x.mu.Unlock()
	seq, err := x.send(req)
	if err != nil {
		return nil, err
	}
	reply, err := x.readReply(seq)
	if err != nil {
		return nil, err
	}
	if p := x.pending; p != nil {
		x.pending = nil
		return reply, p
	}
	return reply, nil
}

// sendOnly sends a request that has no reply.
func (x *Conn) sendOnly(req []byte) error {
	x.mu.Lock()
	defer x.mu.Unlock()
	_, err := x.send(req)
	return err
}

// Sync waits until the server has processed every request sent so far and
// returns the first error one of them caused, if any.
func (x *Conn) Sync() error {
	// GetInputFocus is the classic no-op round trip.
	_, err := x.roundTrip(request(4, opGetInputFocus, 0))
	return err
}

// request returns a zeroed request of size bytes (a multiple of 4) with its
// opcode and data byte set.
func request(size int, opcode, data uint8) []byte {
	b := make([]byte, size)
	b[0], b[1] = opcode, data
	return b
}
