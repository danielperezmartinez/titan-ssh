package main

import (
	"errors"
	"testing"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/protocol"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/screen"
)

// flatScreen is a w×h screen of one colour, with no cursor.
type flatScreen struct{ w, h int }

func (s flatScreen) Size() (int, int, error) { return s.w, s.h, nil }
func (s flatScreen) Capture(dst []uint32, w, h int) error {
	for i := range dst {
		dst[i] = 0x336699
	}
	return nil
}
func (s flatScreen) Cursor() (screen.Cursor, error) { return screen.Cursor{}, errors.New("no cursor") }

// fakeHelperScreen makes the helper serve open's result for one test.
func fakeHelperScreen(t *testing.T, open func() (screen.Capturer, func(), error)) {
	old := openHelperScreen
	openHelperScreen = open
	t.Cleanup(func() { openHelperScreen = old })
}

func TestScreenConnectionReachesTheHelpersCapturer(t *testing.T) {
	fastDesktopWaits(t)
	dir := testStateDir(t)
	closed := make(chan struct{})
	fakeHelperScreen(t, func() (screen.Capturer, func(), error) {
		return flatScreen{w: 64, h: 48}, func() { close(closed) }, nil
	})
	conn, err := connectDesktopWith(dir, desktopScreenMagic, func() error {
		startTestHelper(t, dir, &recordingInjector{})
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := screenReady(conn); err != nil {
		t.Fatal(err)
	}
	if _, err := conn.Write(protocol.Encode(protocol.Frame{Type: protocol.TypeScreenHello, Quality: 0})); err != nil {
		t.Fatal(err)
	}
	dec := &protocol.Decoder{Max: protocol.MaxPayload}
	buf := make([]byte, 64*1024)
	_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	var size *protocol.Frame
	for size == nil {
		n, err := conn.Read(buf)
		if err != nil {
			t.Fatalf("no SCREEN_SIZE: %v", err)
		}
		frames, _ := dec.Feed(buf[:n])
		for i := range frames {
			if frames[i].Type == protocol.TypeScreenSize {
				size = &frames[i]
			}
		}
	}
	if size.Width != 64 || size.Height != 48 {
		t.Fatalf("SCREEN_SIZE %dx%d, want 64x48", size.Width, size.Height)
	}
	conn.Close()
	select {
	case <-closed:
	case <-time.After(3 * time.Second):
		t.Fatal("the helper did not close the capturer when the client left")
	}
}

func TestScreenConnectionReportsTheHelpersError(t *testing.T) {
	fastDesktopWaits(t)
	dir := testStateDir(t)
	fakeHelperScreen(t, func() (screen.Capturer, func(), error) {
		return nil, nil, withCode(codeDesktopInactive, errors.New("the Windows session is disconnected"))
	})
	conn, err := connectDesktopWith(dir, desktopScreenMagic, func() error {
		startTestHelper(t, dir, &recordingInjector{})
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	err = screenReady(conn)
	var ae *agentError
	if !errors.As(err, &ae) || ae.Code != codeDesktopInactive || ae.Err.Error() != "the Windows session is disconnected" {
		t.Fatalf("screenReady = %v, want E_DESKTOP_INACTIVE with the helper's message", err)
	}
}
