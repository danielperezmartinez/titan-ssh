// Package inject applies a mouse pad session's input frames (ADR-0016) to the
// desktop: Serve reads them from a connection and drives an Injector, the
// per-system backend that moves the pointer and types.
package inject

import (
	"errors"
	"io"
	"sync"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/protocol"
)

// ErrUnsupported reports a system with no injector backend yet.
var ErrUnsupported = errors.New("input injection is not supported on this system")

// Injector puts input into the desktop. Implementations need not be safe for
// concurrent use: Serve calls them under one lock shared by every connection.
type Injector interface {
	// Move moves the pointer by dx, dy pixels.
	Move(dx, dy int) error
	// Button presses or releases a protocol.Button*.
	Button(button uint8, down bool) error
	// Scroll scrolls by dx, dy in 1/120 of a notch; dy > 0 is up, dx > 0 right.
	Scroll(dx, dy int) error
	// Text types s as characters.
	Text(s string) error
	// Key presses or releases a protocol.Key* code; with ActionPress the mods
	// are held around the tap.
	Key(key uint16, mods uint8, action uint8) error
	// Blocked reports whether input cannot reach the desktop right now (it is
	// locked, or a secure desktop is in front).
	Blocked() bool
}

// BlockedPoll is how often Serve re-checks Blocked while a client is
// connected, so the client hears about a lock or unlock. A var so tests can
// shorten it.
var BlockedPoll = time.Second

// Serve runs one input connection until it closes: it announces INPUT_READY,
// applies every input frame and tells the client whenever the blocked state
// changes. When the connection ends, however it ends, it releases the buttons
// and keys the client left pressed, so a drag cut by a network drop does not
// leave a button held on the destination. mu serializes the injector across
// connections.
func Serve(conn io.ReadWriter, inj Injector, mu *sync.Mutex) error {
	var writeMu sync.Mutex
	send := func(f protocol.Frame) error {
		writeMu.Lock()
		defer writeMu.Unlock()
		_, err := conn.Write(protocol.Encode(f))
		return err
	}

	held := newHeld()
	defer func() {
		mu.Lock()
		held.releaseAll(inj)
		mu.Unlock()
	}()

	mu.Lock()
	blocked := inj.Blocked()
	mu.Unlock()
	if err := send(protocol.Frame{Type: protocol.TypeInputReady, Blocked: blocked}); err != nil {
		return err
	}

	// Watch the blocked state while the connection lives.
	done := make(chan struct{})
	defer close(done)
	var stateMu sync.Mutex
	report := func(now bool) {
		stateMu.Lock()
		defer stateMu.Unlock()
		if now != blocked {
			blocked = now
			_ = send(protocol.Frame{Type: protocol.TypeInputReady, Blocked: now})
		}
	}
	poll := BlockedPoll
	go func() {
		t := time.NewTicker(poll)
		defer t.Stop()
		for {
			select {
			case <-done:
				return
			case <-t.C:
				mu.Lock()
				now := inj.Blocked()
				mu.Unlock()
				report(now)
			}
		}
	}()

	dec := &protocol.Decoder{Max: protocol.MaxInputPayload}
	buf := make([]byte, 16*1024)
	for {
		n, rerr := conn.Read(buf)
		if n > 0 {
			frames, derr := dec.Feed(buf[:n])
			for _, f := range frames {
				mu.Lock()
				err := apply(inj, held, f)
				var now bool
				if err != nil {
					// A refused event usually means the desktop just locked.
					now = inj.Blocked()
				}
				mu.Unlock()
				if err != nil {
					report(now)
				}
			}
			if derr != nil {
				return derr
			}
		}
		if rerr != nil {
			if errors.Is(rerr, io.EOF) {
				return nil
			}
			return rerr
		}
	}
}

// apply drives inj with one frame and records what stays pressed. Frames that
// are not input (a stray BYE, say) are ignored.
func apply(inj Injector, h *held, f protocol.Frame) error {
	switch f.Type {
	case protocol.TypePointerMove:
		return inj.Move(int(f.DX), int(f.DY))
	case protocol.TypePointerButton:
		h.button(f.Button, f.Pressed)
		return inj.Button(f.Button, f.Pressed)
	case protocol.TypeScroll:
		return inj.Scroll(int(f.DX), int(f.DY))
	case protocol.TypeText:
		return inj.Text(string(f.Bytes))
	case protocol.TypeKey:
		switch f.Action {
		case protocol.ActionDown:
			h.key(f.Key, true)
		case protocol.ActionUp:
			h.key(f.Key, false)
		case protocol.ActionPress:
		default:
			return nil // an action from a newer client
		}
		return inj.Key(f.Key, f.Mods, f.Action)
	}
	return nil
}

// held is what a connection has pressed and not yet released.
type held struct {
	buttons map[uint8]bool
	keys    map[uint16]bool
}

func newHeld() *held { return &held{buttons: map[uint8]bool{}, keys: map[uint16]bool{}} }

func (h *held) button(b uint8, down bool) {
	if down {
		h.buttons[b] = true
	} else {
		delete(h.buttons, b)
	}
}

func (h *held) key(k uint16, down bool) {
	if down {
		h.keys[k] = true
	} else {
		delete(h.keys, k)
	}
}

func (h *held) releaseAll(inj Injector) {
	for b := range h.buttons {
		_ = inj.Button(b, false)
	}
	for k := range h.keys {
		_ = inj.Key(k, 0, protocol.ActionUp)
	}
	h.buttons, h.keys = map[uint8]bool{}, map[uint16]bool{}
}
