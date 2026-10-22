package screen

import (
	"errors"
	"fmt"
	"strings"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

// GDI captures the Windows virtual desktop, every monitor as one image, with
// BitBlt from the screen DC (ADR-0031 §4). It works everywhere a desktop
// does, virtual machines and remote desktop sessions included. It must run on
// the user's interactive desktop: the desktop helper, never session 0.
//
// Coordinates are physical pixels of the virtual desktop, shifted so that
// its top-left corner is (0, 0) of the image; the process is made per-monitor
// DPI aware so that Windows does not scale them.
type GDI struct {
	mem    uintptr // memory DC holding dib
	dib    uintptr
	bits   unsafe.Pointer
	w, h   int
	cursor cursorCache
	// The last lock lookup.
	locked bool
	lockAt time.Time
}

var (
	user32                        = windows.NewLazySystemDLL("user32.dll")
	gdi32                         = windows.NewLazySystemDLL("gdi32.dll")
	wtsapi32                      = windows.NewLazySystemDLL("wtsapi32.dll")
	procGetDC                     = user32.NewProc("GetDC")
	procReleaseDC                 = user32.NewProc("ReleaseDC")
	procGetSystemMetrics          = user32.NewProc("GetSystemMetrics")
	procGetCursorInfo             = user32.NewProc("GetCursorInfo")
	procGetIconInfo               = user32.NewProc("GetIconInfo")
	procOpenInputDesktop          = user32.NewProc("OpenInputDesktop")
	procCloseDesktop              = user32.NewProc("CloseDesktop")
	procSetProcessDpiAwarenessCtx = user32.NewProc("SetProcessDpiAwarenessContext")
	procSetProcessDPIAware        = user32.NewProc("SetProcessDPIAware")
	procCreateCompatibleDC        = gdi32.NewProc("CreateCompatibleDC")
	procCreateDIBSection          = gdi32.NewProc("CreateDIBSection")
	procSelectObject              = gdi32.NewProc("SelectObject")
	procBitBlt                    = gdi32.NewProc("BitBlt")
	procDeleteObject              = gdi32.NewProc("DeleteObject")
	procDeleteDC                  = gdi32.NewProc("DeleteDC")
	procGetDIBits                 = gdi32.NewProc("GetDIBits")
	procGetObjectW                = gdi32.NewProc("GetObjectW")
	procWTSQuerySessionInfoW      = wtsapi32.NewProc("WTSQuerySessionInformationW")
	procWTSFreeMemory             = wtsapi32.NewProc("WTSFreeMemory")
	procWTSEnumerateProcessesW    = wtsapi32.NewProc("WTSEnumerateProcessesW")
)

const (
	smXVirtualScreen  = 76
	smYVirtualScreen  = 77
	smCXVirtualScreen = 78
	smCYVirtualScreen = 79

	srcCopy       = 0x00CC0020
	dibRGBColors  = 0
	cursorShowing = 0x00000001

	// DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2
	dpiPerMonitorV2 = ^uintptr(3) // (HANDLE)-4
)

// bitmapInfo is a BITMAPINFO for 32-bit BI_RGB pixels, which needs no
// colour table.
type bitmapInfo struct {
	size          uint32
	width, height int32
	planes        uint16
	bitCount      uint16
	compression   uint32
	sizeImage     uint32
	xppm, yppm    int32
	clrUsed       uint32
	clrImportant  uint32
	_             [4]byte // bmiColors[1]
}

// topDown32 describes a w×h top-down 32-bit image.
func topDown32(w, h int) bitmapInfo {
	return bitmapInfo{size: 40, width: int32(w), height: -int32(h), planes: 1, bitCount: 32}
}

// NewGDI returns a capturer of the virtual desktop. It makes the process
// per-monitor DPI aware (a second call, after the injector's, changes
// nothing).
func NewGDI() (*GDI, error) {
	if procSetProcessDpiAwarenessCtx.Find() == nil {
		procSetProcessDpiAwarenessCtx.Call(dpiPerMonitorV2)
	} else if procSetProcessDPIAware.Find() == nil {
		procSetProcessDPIAware.Call()
	}
	mem, _, err := procCreateCompatibleDC.Call(0)
	if mem == 0 {
		return nil, fmt.Errorf("CreateCompatibleDC: %w", err)
	}
	return &GDI{mem: mem}, nil
}

// Close frees the capturer's GDI objects.
func (c *GDI) Close() {
	if c.dib != 0 {
		procDeleteObject.Call(c.dib)
		c.dib = 0
	}
	if c.mem != 0 {
		procDeleteDC.Call(c.mem)
		c.mem = 0
	}
}

func metric(i int) int {
	r, _, _ := procGetSystemMetrics.Call(uintptr(i))
	return int(int32(r))
}

// virtualDesktop is the virtual desktop's rectangle in screen coordinates.
func virtualDesktop() (x, y, w, h int) {
	return metric(smXVirtualScreen), metric(smYVirtualScreen), metric(smCXVirtualScreen), metric(smCYVirtualScreen)
}

// Size is the virtual desktop's size, which follows monitors being added or
// removed and changes of resolution or scale.
func (c *GDI) Size() (int, int, error) {
	_, _, w, h := virtualDesktop()
	if w <= 0 || h <= 0 {
		return 0, 0, errors.New("no virtual desktop")
	}
	return w, h, nil
}

// Capture copies the virtual desktop into dst. While the desktop is out of
// reach (it locked between Blocked and here), BitBlt fails and dst keeps the
// last image: Serve finds Blocked on its next round.
func (c *GDI) Capture(dst []uint32, w, h int) error {
	if err := c.ensureDIB(w, h); err != nil {
		return err
	}
	vx, vy, _, _ := virtualDesktop()
	screen, _, err := procGetDC.Call(0)
	if screen == 0 {
		if c.Blocked() {
			return nil
		}
		return fmt.Errorf("GetDC: %w", err)
	}
	r, _, err := procBitBlt.Call(c.mem, 0, 0, uintptr(w), uintptr(h), screen, uintptr(vx), uintptr(vy), srcCopy)
	procReleaseDC.Call(0, screen)
	if r == 0 {
		if c.Blocked() {
			return nil
		}
		return fmt.Errorf("BitBlt: %w", err)
	}
	src := unsafe.Slice((*uint32)(c.bits), w*h)
	for i, p := range src[:len(dst)] {
		dst[i] = p & 0x00ffffff
	}
	return nil
}

// ensureDIB keeps a w×h DIB section selected into the memory DC.
func (c *GDI) ensureDIB(w, h int) error {
	if c.dib != 0 && c.w == w && c.h == h {
		return nil
	}
	if c.dib != 0 {
		procDeleteObject.Call(c.dib)
		c.dib, c.bits = 0, nil
	}
	bi := topDown32(w, h)
	var bits unsafe.Pointer
	dib, _, err := procCreateDIBSection.Call(c.mem, uintptr(unsafe.Pointer(&bi)), dibRGBColors, uintptr(unsafe.Pointer(&bits)), 0, 0)
	if dib == 0 || bits == nil {
		return fmt.Errorf("CreateDIBSection %dx%d: %w", w, h, err)
	}
	procSelectObject.Call(c.mem, dib)
	c.dib, c.bits, c.w, c.h = dib, bits, w, h
	return nil
}

// Blocked reports whether the desktop is out of reach: the session is locked
// or disconnected, or the input desktop is Winlogon's (the sign-in prompt,
// UAC or Ctrl+Alt+Del in front), which a user process cannot open. The lock
// screen itself is on the user's own desktop on Windows 11 and could be
// captured, but nothing past it can be reached from here (ADR-0031 §4).
// Serve asks on every round, so the lock is looked up at most every
// lockPoll.
func (c *GDI) Blocked() bool {
	if InputDesktopBlocked() || SessionDisconnected() {
		return true
	}
	if time.Since(c.lockAt) >= lockPoll {
		c.locked, c.lockAt = SessionLocked(), time.Now()
	}
	return c.locked
}

const lockPoll = 500 * time.Millisecond

// SessionLocked reports whether this process's Windows session is locked:
// LogonUI runs in it while it is. The session's own lock flag
// (WTSSessionInfoEx) reads 0, locked, either way on Windows 11. The process
// list comes from WTSEnumerateProcesses, which gives each one's session
// without opening it: LogonUI runs as SYSTEM.
func SessionLocked() bool {
	var self uint32
	if windows.ProcessIdToSessionId(windows.GetCurrentProcessId(), &self) != nil {
		return false
	}
	// WTS_PROCESS_INFOW
	type processInfo struct {
		session, pid uint32
		name         *uint16
		sid          uintptr
	}
	var list *processInfo
	var n uint32
	if r, _, _ := procWTSEnumerateProcessesW.Call(0, 0, 1, uintptr(unsafe.Pointer(&list)), uintptr(unsafe.Pointer(&n))); r == 0 || list == nil {
		return false
	}
	defer procWTSFreeMemory.Call(uintptr(unsafe.Pointer(list)))
	for _, p := range unsafe.Slice(list, n) {
		if p.session == self && strings.EqualFold(windows.UTF16PtrToString(p.name), "LogonUI.exe") {
			return true
		}
	}
	return false
}

// InputDesktopBlocked reports whether this process cannot open the input
// desktop.
func InputDesktopBlocked() bool {
	const desktopReadObjects = 0x0001
	h, _, _ := procOpenInputDesktop.Call(0, 0, desktopReadObjects)
	if h == 0 {
		return true
	}
	procCloseDesktop.Call(h)
	return false
}

// SessionDisconnected reports whether this process's Windows session has no
// screen: a remote desktop closed without signing out, or a user switched
// away from (fast user switching).
func SessionDisconnected() bool {
	const (
		wtsCurrentSession = ^uintptr(0) // WTS_CURRENT_SESSION, (DWORD)-1
		wtsConnectState   = 8
		wtsDisconnected   = 4
	)
	var buf *uint32
	var n uint32
	r, _, _ := procWTSQuerySessionInfoW.Call(0, wtsCurrentSession&0xffffffff, wtsConnectState, uintptr(unsafe.Pointer(&buf)), uintptr(unsafe.Pointer(&n)))
	if r == 0 || buf == nil {
		return false
	}
	defer procWTSFreeMemory.Call(uintptr(unsafe.Pointer(buf)))
	return n >= 4 && *buf == wtsDisconnected
}

// cursorInfo is CURSORINFO.
type cursorInfo struct {
	size   uint32
	flags  uint32
	cursor uintptr
	x, y   int32
}

// iconInfo is ICONINFO.
type iconInfo struct {
	icon         int32
	hotX, hotY   uint32
	mask, colour uintptr
}

// bitmap is BITMAP.
type bitmap struct {
	typ, width, height, widthBytes int32
	planes, bitsPixel              uint16
	bits                           uintptr
}

// cursorCache keeps the image of the last cursor handle, which Windows
// shares between every window showing the same cursor.
type cursorCache struct {
	handle uintptr
	serial uint32
	shape  Cursor
}

// Cursor reports the pointer's position on the image and, when its handle
// changes, its image.
func (c *GDI) Cursor() (Cursor, error) {
	ci := cursorInfo{}
	ci.size = uint32(unsafe.Sizeof(ci))
	if r, _, err := procGetCursorInfo.Call(uintptr(unsafe.Pointer(&ci))); r == 0 {
		return Cursor{}, fmt.Errorf("GetCursorInfo: %w", err)
	}
	vx, vy, _, _ := virtualDesktop()
	out := Cursor{X: int(ci.x) - vx, Y: int(ci.y) - vy, Visible: ci.flags&cursorShowing != 0 && ci.cursor != 0}
	if ci.cursor != 0 && ci.cursor != c.cursor.handle {
		if shape, err := readCursorShape(ci.cursor); err == nil {
			c.cursor.serial++
			c.cursor.handle, c.cursor.shape = ci.cursor, shape
		}
	}
	s := c.cursor.shape
	out.Serial, out.Width, out.Height, out.HotX, out.HotY, out.Pixels = c.cursor.serial, s.Width, s.Height, s.HotX, s.HotY, s.Pixels
	if out.Pixels == nil {
		out.Visible = false
	}
	return out, nil
}

// readCursorShape reads a cursor's image as straight-alpha ARGB.
func readCursorShape(hc uintptr) (Cursor, error) {
	var ii iconInfo
	if r, _, err := procGetIconInfo.Call(hc, uintptr(unsafe.Pointer(&ii))); r == 0 {
		return Cursor{}, fmt.Errorf("GetIconInfo: %w", err)
	}
	if ii.mask != 0 {
		defer procDeleteObject.Call(ii.mask)
	}
	if ii.colour != 0 {
		defer procDeleteObject.Call(ii.colour)
	}
	mask, mw, mh, err := bitmapPixels(ii.mask)
	if err != nil {
		return Cursor{}, err
	}
	var px []uint32
	w, h := mw, mh
	if ii.colour != 0 {
		colour, cw, ch, err := bitmapPixels(ii.colour)
		if err != nil {
			return Cursor{}, err
		}
		if cw != mw || ch != mh {
			return Cursor{}, errors.New("cursor mask and colour differ in size")
		}
		px = colourCursor(colour, mask)
	} else {
		h = mh / 2
		px = monochromeCursor(mask[:w*h], mask[w*h:w*h*2], w, h)
	}
	return Cursor{Width: w, Height: h, HotX: int(ii.hotX), HotY: int(ii.hotY), Pixels: px}, nil
}

// bitmapPixels reads a bitmap as top-down 32-bit pixels. A monochrome one
// comes out as 0x000000 and 0xffffff.
func bitmapPixels(hbm uintptr) ([]uint32, int, int, error) {
	if hbm == 0 {
		return nil, 0, 0, errors.New("no cursor bitmap")
	}
	var bm bitmap
	if r, _, err := procGetObjectW.Call(hbm, unsafe.Sizeof(bm), uintptr(unsafe.Pointer(&bm))); r == 0 {
		return nil, 0, 0, fmt.Errorf("GetObject: %w", err)
	}
	w, h := int(bm.width), int(bm.height)
	if w <= 0 || h <= 0 || w > 1024 || h > 2048 {
		return nil, 0, 0, fmt.Errorf("cursor bitmap of %dx%d", w, h)
	}
	dc, _, err := procGetDC.Call(0)
	if dc == 0 {
		return nil, 0, 0, fmt.Errorf("GetDC: %w", err)
	}
	defer procReleaseDC.Call(0, dc)
	px := make([]uint32, w*h)
	bi := topDown32(w, h)
	if r, _, err := procGetDIBits.Call(dc, hbm, 0, uintptr(h), uintptr(unsafe.Pointer(&px[0])), uintptr(unsafe.Pointer(&bi)), dibRGBColors); r == 0 {
		return nil, 0, 0, fmt.Errorf("GetDIBits: %w", err)
	}
	return px, w, h, nil
}
