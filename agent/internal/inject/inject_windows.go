package inject

import (
	"errors"
	"fmt"
	"time"
	"unicode/utf16"
	"unsafe"

	"golang.org/x/sys/windows"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/protocol"
)

// The Windows backend is SendInput (ADR-0016 §3). It only works from a
// process on the user's interactive desktop: the desktop helper, never the
// daemon in session 0.

var (
	user32                        = windows.NewLazySystemDLL("user32.dll")
	procSendInput                 = user32.NewProc("SendInput")
	procGetCursorPos              = user32.NewProc("GetCursorPos")
	procGetSystemMetrics          = user32.NewProc("GetSystemMetrics")
	procMonitorFromPoint          = user32.NewProc("MonitorFromPoint")
	procGetMonitorInfoW           = user32.NewProc("GetMonitorInfoW")
	procMapVirtualKeyW            = user32.NewProc("MapVirtualKeyW")
	procOpenInputDesktop          = user32.NewProc("OpenInputDesktop")
	procCloseDesktop              = user32.NewProc("CloseDesktop")
	procSetProcessDpiAwarenessCtx = user32.NewProc("SetProcessDpiAwarenessContext")
	procSetProcessDPIAware        = user32.NewProc("SetProcessDPIAware")
)

const (
	inputMouse    = 0
	inputKeyboard = 1

	mouseMove        = 0x0001
	mouseLeftDown    = 0x0002
	mouseLeftUp      = 0x0004
	mouseRightDown   = 0x0008
	mouseRightUp     = 0x0010
	mouseMiddleDown  = 0x0020
	mouseMiddleUp    = 0x0040
	mouseWheel       = 0x0800
	mouseHWheel      = 0x1000
	mouseVirtualDesk = 0x4000
	mouseAbsolute    = 0x8000

	keyExtended = 0x0001
	keyUp       = 0x0002
	keyUnicode  = 0x0004

	smXVirtualScreen  = 76
	smYVirtualScreen  = 77
	smCXVirtualScreen = 78
	smCYVirtualScreen = 79

	mapVKToVSC = 0

	// DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2
	dpiPerMonitorV2 = ^uintptr(3) // (HANDLE)-4
)

// mouseInput and keyInput are INPUT with its MOUSEINPUT or KEYBDINPUT arm.
// Each arm is its own struct so that, like the C union, it is aligned to its
// pointer-sized field: Go then pads after typ on 64-bit Windows. MOUSEINPUT is
// the larger arm; keyInput pads to the same size, 40 bytes on 64-bit Windows
// and 28 on 32-bit.
type mouseInput struct {
	typ uint32
	mi  mouseArm
}

type mouseArm struct {
	dx, dy    int32
	mouseData uint32
	flags     uint32
	time      uint32
	extra     uintptr
}

type keyInput struct {
	typ uint32
	ki  keyArm
	_   [8]byte
}

type keyArm struct {
	vk    uint16
	scan  uint16
	flags uint32
	time  uint32
	extra uintptr
}

func mouseEvent(flags uint32) mouseInput {
	return mouseInput{typ: inputMouse, mi: mouseArm{flags: flags}}
}

func init() {
	if unsafe.Sizeof(mouseInput{}) != unsafe.Sizeof(keyInput{}) {
		panic("inject: INPUT arms differ in size")
	}
}

type windowsInjector struct {
	// Where the last move sent the cursor, and when. SendInput is
	// asynchronous, so right after a move GetCursorPos may still report an
	// older position; the next move then builds on the target instead.
	moveTo  point
	movedAt time.Time
}

type point struct{ X, Y int32 }

// moveSettle is how long a move may take to show in GetCursorPos.
const moveSettle = 100 * time.Millisecond

// New returns the Windows injector. It makes the process per-monitor DPI
// aware first, so cursor positions are physical pixels on every monitor.
func New() (Injector, error) {
	if procSetProcessDpiAwarenessCtx.Find() == nil {
		procSetProcessDpiAwarenessCtx.Call(dpiPerMonitorV2)
	} else if procSetProcessDPIAware.Find() == nil {
		procSetProcessDPIAware.Call()
	}
	return &windowsInjector{}, nil
}

func send[T mouseInput | keyInput](inputs []T) error {
	if len(inputs) == 0 {
		return nil
	}
	n, _, err := procSendInput.Call(uintptr(len(inputs)), uintptr(unsafe.Pointer(&inputs[0])), unsafe.Sizeof(inputs[0]))
	if int(n) != len(inputs) {
		return fmt.Errorf("SendInput inserted %d of %d events: %w", n, len(inputs), err)
	}
	return nil
}

func metric(i int) int {
	r, _, _ := procGetSystemMetrics.Call(uintptr(i))
	return int(int32(r))
}

// Move places the pointer at its current position plus the delta, as an
// absolute position on the virtual desktop. A relative SendInput would go
// through Windows' pointer acceleration on top of the client's curve. The
// target is kept inside the monitor nearest to it, as Windows would, so it is
// always where the cursor ends up.
func (w *windowsInjector) Move(dx, dy int) error {
	var cur point
	if r, _, err := procGetCursorPos.Call(uintptr(unsafe.Pointer(&cur))); r == 0 {
		return fmt.Errorf("GetCursorPos: %w", err)
	}
	from := cur
	if !w.movedAt.IsZero() && time.Since(w.movedAt) < moveSettle {
		// Moves in a row: chain them on the last target, which the cursor
		// may not show yet (it can lag several moves behind).
		from = w.moveTo
	}
	vx, vy := metric(smXVirtualScreen), metric(smYVirtualScreen)
	vw, vh := metric(smCXVirtualScreen), metric(smCYVirtualScreen)
	if vw <= 0 || vh <= 0 {
		return errors.New("no virtual desktop")
	}
	to := clampToMonitor(point{X: from.X + int32(dx), Y: from.Y + int32(dy)})
	in := mouseEvent(mouseMove | mouseAbsolute | mouseVirtualDesk)
	in.mi.dx, in.mi.dy = int32(normalize(int(to.X)-vx, vw)), int32(normalize(int(to.Y)-vy, vh))
	if err := send([]mouseInput{in}); err != nil {
		return err
	}
	w.moveTo, w.movedAt = to, time.Now()
	return nil
}

// clampToMonitor keeps p inside the monitor nearest to it.
func clampToMonitor(p point) point {
	const monitorDefaultToNearest = 2
	var mon uintptr
	if unsafe.Sizeof(uintptr(0)) == 8 {
		// A POINT by value travels in one register on 64-bit Windows.
		mon, _, _ = procMonitorFromPoint.Call(uintptr(uint32(p.X))|uintptr(uint32(p.Y))<<32, monitorDefaultToNearest)
	} else {
		mon, _, _ = procMonitorFromPoint.Call(uintptr(p.X), uintptr(p.Y), monitorDefaultToNearest)
	}
	info := struct {
		size          uint32
		monitor, work [4]int32 // left, top, right, bottom
		flags         uint32
	}{}
	info.size = uint32(unsafe.Sizeof(info))
	if mon == 0 {
		return p
	}
	if r, _, _ := procGetMonitorInfoW.Call(mon, uintptr(unsafe.Pointer(&info))); r == 0 {
		return p
	}
	return point{
		X: int32(clamp(int(p.X), int(info.monitor[0]), int(info.monitor[2])-1)),
		Y: int32(clamp(int(p.Y), int(info.monitor[1]), int(info.monitor[3])-1)),
	}
}

// normalize maps pixel px of a span of size pixels to SendInput's 0..65535
// absolute range, rounding up so that Windows, which maps back with
// px = n*size/65536, lands on px exactly.
func normalize(px, size int) int {
	return (px*65536 + size - 1) / size
}

func clamp(v, lo, hi int) int {
	return max(lo, min(v, hi))
}

func (w *windowsInjector) Button(button uint8, down bool) error {
	var flags uint32
	switch button {
	case protocol.ButtonLeft:
		flags = pick(down, mouseLeftDown, mouseLeftUp)
	case protocol.ButtonRight:
		flags = pick(down, mouseRightDown, mouseRightUp)
	case protocol.ButtonMiddle:
		flags = pick(down, mouseMiddleDown, mouseMiddleUp)
	default:
		return nil // a button from a newer client
	}
	return send([]mouseInput{mouseEvent(flags)})
}

func pick(down bool, d, u uint32) uint32 {
	if down {
		return d
	}
	return u
}

func (w *windowsInjector) Scroll(dx, dy int) error {
	var in []mouseInput
	if dy != 0 {
		e := mouseEvent(mouseWheel)
		e.mi.mouseData = uint32(int32(dy))
		in = append(in, e)
	}
	if dx != 0 {
		e := mouseEvent(mouseHWheel)
		e.mi.mouseData = uint32(int32(dx))
		in = append(in, e)
	}
	return send(in)
}

// textBatch is about how many key events Text hands SendInput at a time; a
// batch ends between characters, never inside one.
const textBatch = 256

// Text types each character as a Unicode key event, so it does not depend on
// the keyboard layout. Line breaks and tabs go as the Enter and Tab keys,
// which applications handle better than the characters. The events go out in
// batches of textBatch, so a long text never needs one large allocation.
func (w *windowsInjector) Text(s string) error {
	in := make([]keyInput, 0, textBatch)
	flush := func() error {
		err := send(in)
		in = in[:0]
		return err
	}
	for _, r := range s {
		if len(in) >= textBatch {
			if err := flush(); err != nil {
				return err
			}
		}
		switch r {
		case '\r':
			continue
		case '\n':
			in = append(in, vkTap(vkReturn)...)
			continue
		case '\t':
			in = append(in, vkTap(vkTab)...)
			continue
		}
		for _, u := range utf16.Encode([]rune{r}) {
			in = append(in,
				keyInput{typ: inputKeyboard, ki: keyArm{scan: u, flags: keyUnicode}},
				keyInput{typ: inputKeyboard, ki: keyArm{scan: u, flags: keyUnicode | keyUp}})
		}
	}
	return send(in)
}

func (w *windowsInjector) Key(key uint16, mods uint8, action uint8) error {
	vk, ok := virtualKey(key)
	if !ok {
		return nil // a key from a newer client
	}
	switch action {
	case protocol.ActionDown:
		return send([]keyInput{vkEvent(vk, false)})
	case protocol.ActionUp:
		return send([]keyInput{vkEvent(vk, true)})
	}
	var in []keyInput
	held := modifierKeys(mods)
	for _, m := range held {
		in = append(in, vkEvent(m, false))
	}
	in = append(in, vkTap(vk)...)
	for i := len(held) - 1; i >= 0; i-- {
		in = append(in, vkEvent(held[i], true))
	}
	return send(in)
}

// Blocked reports whether the input desktop is out of reach: while the
// session is locked, or UAC or Ctrl+Alt+Del is in front, the input desktop is
// Winlogon's, which a user process cannot open.
func (w *windowsInjector) Blocked() bool {
	const desktopReadObjects = 0x0001
	h, _, _ := procOpenInputDesktop.Call(0, 0, desktopReadObjects)
	if h == 0 {
		return true
	}
	procCloseDesktop.Call(h)
	return false
}

func vkTap(vk uint16) []keyInput {
	return []keyInput{vkEvent(vk, false), vkEvent(vk, true)}
}

func vkEvent(vk uint16, up bool) keyInput {
	scan, _, _ := procMapVirtualKeyW.Call(uintptr(vk), mapVKToVSC)
	flags := uint32(0)
	if extendedKeys[vk] {
		flags |= keyExtended
	}
	if up {
		flags |= keyUp
	}
	return keyInput{typ: inputKeyboard, ki: keyArm{vk: vk, scan: uint16(scan), flags: flags}}
}

func modifierKeys(mods uint8) []uint16 {
	var keys []uint16
	if mods&protocol.ModCtrl != 0 {
		keys = append(keys, vkLControl)
	}
	if mods&protocol.ModAlt != 0 {
		keys = append(keys, vkLMenu)
	}
	if mods&protocol.ModShift != 0 {
		keys = append(keys, vkLShift)
	}
	if mods&protocol.ModMeta != 0 {
		keys = append(keys, vkLWin)
	}
	return keys
}

// Windows virtual-key codes the protocol's keys map to.
const (
	vkBack       = 0x08
	vkTab        = 0x09
	vkReturn     = 0x0D
	vkEscape     = 0x1B
	vkSpace      = 0x20
	vkPrior      = 0x21
	vkNext       = 0x22
	vkEnd        = 0x23
	vkHome       = 0x24
	vkLeft       = 0x25
	vkUp         = 0x26
	vkRight      = 0x27
	vkDown       = 0x28
	vkSnapshot   = 0x2C
	vkInsert     = 0x2D
	vkDelete     = 0x2E
	vkLWin       = 0x5B
	vkApps       = 0x5D
	vkF1         = 0x70
	vkLShift     = 0xA0
	vkLControl   = 0xA2
	vkLMenu      = 0xA4
	vkVolumeMute = 0xAD
	vkVolumeDown = 0xAE
	vkVolumeUp   = 0xAF
	vkMediaNext  = 0xB0
	vkMediaPrev  = 0xB1
	vkMediaPlay  = 0xB3
)

var namedKeys = map[uint16]uint16{
	protocol.KeyEnter:          vkReturn,
	protocol.KeyEscape:         vkEscape,
	protocol.KeyBackspace:      vkBack,
	protocol.KeyTab:            vkTab,
	protocol.KeySpace:          vkSpace,
	protocol.KeyDelete:         vkDelete,
	protocol.KeyInsert:         vkInsert,
	protocol.KeyHome:           vkHome,
	protocol.KeyEnd:            vkEnd,
	protocol.KeyPageUp:         vkPrior,
	protocol.KeyPageDown:       vkNext,
	protocol.KeyArrowLeft:      vkLeft,
	protocol.KeyArrowRight:     vkRight,
	protocol.KeyArrowUp:        vkUp,
	protocol.KeyArrowDown:      vkDown,
	protocol.KeyPrintScreen:    vkSnapshot,
	protocol.KeyContextMenu:    vkApps,
	protocol.KeyVolumeUp:       vkVolumeUp,
	protocol.KeyVolumeDown:     vkVolumeDown,
	protocol.KeyVolumeMute:     vkVolumeMute,
	protocol.KeyMediaPlayPause: vkMediaPlay,
	protocol.KeyMediaNext:      vkMediaNext,
	protocol.KeyMediaPrevious:  vkMediaPrev,
	protocol.KeyShift:          vkLShift,
	protocol.KeyCtrl:           vkLControl,
	protocol.KeyAlt:            vkLMenu,
	protocol.KeyMeta:           vkLWin,
}

// extendedKeys need KEYEVENTF_EXTENDEDKEY: without it Windows reads the
// navigation keys as their numeric-keypad twins.
var extendedKeys = map[uint16]bool{
	vkPrior: true, vkNext: true, vkEnd: true, vkHome: true,
	vkLeft: true, vkUp: true, vkRight: true, vkDown: true,
	vkInsert: true, vkDelete: true, vkSnapshot: true, vkLWin: true, vkApps: true,
	vkVolumeMute: true, vkVolumeDown: true, vkVolumeUp: true,
	vkMediaNext: true, vkMediaPrev: true, vkMediaPlay: true,
}

func virtualKey(key uint16) (uint16, bool) {
	switch {
	case key >= protocol.KeyF1 && key <= protocol.KeyF12:
		return vkF1 + key - protocol.KeyF1, true
	case key >= protocol.KeyA && key <= protocol.KeyZ:
		return 'A' + key - protocol.KeyA, true
	case key >= protocol.Key0 && key <= protocol.Key9:
		return '0' + key - protocol.Key0, true
	}
	vk, ok := namedKeys[key]
	return vk, ok
}
