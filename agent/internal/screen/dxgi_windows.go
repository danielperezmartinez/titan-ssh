package screen

import (
	"errors"
	"fmt"
	"image"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

// Duplication captures the Windows virtual desktop with DXGI Desktop
// Duplication, one duplication per monitor (ADR-0031 §4), through COM called
// straight from Go. It reports what changed from the dirty and moved
// rectangles Windows gives, so Serve need not compare the whole screen. When
// a duplication cannot be had (an adapter without support, a rotated
// monitor, too many duplications, a secure desktop in front) it captures with
// GDI instead, and tries again a little later. The cursor and the blocked
// state always come from GDI, which sees the cursor of every monitor.
type Duplication struct {
	gdi  *GDI
	outs []*dupOutput
	// The virtual desktop the duplications were made for.
	vx, vy, w, h int
	failedAt     time.Time
	// Err is why the last attempt at a duplication failed, for logs and tests.
	Err error

	damage   []image.Rectangle
	damageOK bool
}

// dupOutput is one monitor's duplication.
type dupOutput struct {
	device, context, dupl, staging *comObject
	// Where the monitor sits on the image, and its size.
	x, y, w, h int
	inMemory   bool // the desktop image is in system memory: map it directly
	img        []uint32
	fresh      bool // img holds a frame
	meta       []byte
}

// dupRetry is how long Capture keeps to GDI after a duplication failed.
const dupRetry = 5 * time.Second

// NewDuplication returns the capturer. It always succeeds when GDI does; the
// first Capture tries the duplications.
func NewDuplication() (*Duplication, error) {
	g, err := NewGDI()
	if err != nil {
		return nil, err
	}
	return &Duplication{gdi: g}, nil
}

// Close releases the duplications and the GDI capturer.
func (d *Duplication) Close() {
	d.release()
	d.gdi.Close()
}

// Duplicating reports whether the last Capture used Desktop Duplication.
func (d *Duplication) Duplicating() bool { return d.outs != nil }

func (d *Duplication) Size() (int, int, error)           { return d.gdi.Size() }
func (d *Duplication) Cursor() (Cursor, error)           { return d.gdi.Cursor() }
func (d *Duplication) Blocked() bool                     { return d.gdi.Blocked() }
func (d *Duplication) Damage() ([]image.Rectangle, bool) { return d.damage, d.damageOK }

// Capture reads every monitor into its place of dst.
func (d *Duplication) Capture(dst []uint32, w, h int) error {
	d.damage, d.damageOK = d.damage[:0], false
	vx, vy, vw, vh := virtualDesktop()
	if d.outs != nil && (vx != d.vx || vy != d.vy || vw != d.w || vh != d.h || vw != w || vh != h) {
		d.release() // the monitors changed
	}
	if d.outs == nil && time.Since(d.failedAt) >= dupRetry {
		if err := d.setup(vx, vy, vw, vh); err != nil {
			d.release()
			d.Err, d.failedAt = err, time.Now()
		}
	}
	if d.outs == nil || vw != w || vh != h {
		return d.gdi.Capture(dst, w, h)
	}
	all := true
	for _, o := range d.outs {
		rects, err := o.acquire()
		if err != nil {
			// ACCESS_LOST (a mode change, a desktop switch) is retried at once.
			d.release()
			d.Err = err
			if !errors.Is(err, errAccessLost) {
				d.failedAt = time.Now()
			}
			return d.gdi.Capture(dst, w, h)
		}
		if rects == nil {
			all = false
		}
		for _, r := range rects {
			d.damage = append(d.damage, r.Add(image.Pt(o.x, o.y)))
		}
	}
	clear(dst[:w*h])
	for _, o := range d.outs {
		for y := 0; y < o.h && o.y+y < h; y++ {
			n := min(o.w, w-o.x)
			copy(dst[(o.y+y)*w+o.x:(o.y+y)*w+o.x+n], o.img[y*o.w:y*o.w+n])
		}
	}
	d.damageOK = all
	return nil
}

// release drops every duplication.
func (d *Duplication) release() {
	for _, o := range d.outs {
		o.release()
	}
	d.outs = nil
}

// setup makes a duplication of every monitor of the desktop.
func (d *Duplication) setup(vx, vy, vw, vh int) error {
	var factory *comObject
	if hr := hresult(procCreateDXGIFactory1.Call(uintptr(unsafe.Pointer(&iidIDXGIFactory1)), uintptr(unsafe.Pointer(&factory)))); hr != nil {
		return fmt.Errorf("CreateDXGIFactory1: %w", hr)
	}
	defer factory.release()
	for a := 0; ; a++ {
		var adapter *comObject
		if hr := factory.call(dxgiFactory1EnumAdapters1, uintptr(a), uintptr(unsafe.Pointer(&adapter))); hr != nil {
			if errors.Is(hr, errNotFound) {
				break
			}
			return fmt.Errorf("EnumAdapters1: %w", hr)
		}
		err := d.setupAdapter(adapter, vx, vy)
		adapter.release()
		if err != nil {
			return err
		}
	}
	if len(d.outs) == 0 {
		return errors.New("no monitor to duplicate")
	}
	d.vx, d.vy, d.w, d.h = vx, vy, vw, vh
	return nil
}

// outputDesc is DXGI_OUTPUT_DESC.
type outputDesc struct {
	name     [32]uint16
	rect     [4]int32 // left, top, right, bottom
	attached int32
	rotation uint32
	monitor  uintptr
}

// duplDesc is DXGI_OUTDUPL_DESC.
type duplDesc struct {
	width, height    uint32
	refreshNum       uint32
	refreshDen       uint32
	format           uint32
	scanlineOrdering uint32
	scaling          uint32
	rotation         uint32
	inSystemMemory   int32
}

func (d *Duplication) setupAdapter(adapter *comObject, vx, vy int) error {
	for i := 0; ; i++ {
		var output *comObject
		if hr := adapter.call(dxgiAdapterEnumOutputs, uintptr(i), uintptr(unsafe.Pointer(&output))); hr != nil {
			if errors.Is(hr, errNotFound) {
				return nil
			}
			return fmt.Errorf("EnumOutputs: %w", hr)
		}
		o, err := duplicate(adapter, output, vx, vy)
		output.release()
		if err != nil {
			return err
		}
		if o != nil {
			d.outs = append(d.outs, o)
		}
	}
}

// duplicate makes the duplication of one output, or nil for an output that
// is not part of the desktop.
func duplicate(adapter, output *comObject, vx, vy int) (*dupOutput, error) {
	var desc outputDesc
	if hr := output.call(dxgiOutputGetDesc, uintptr(unsafe.Pointer(&desc))); hr != nil {
		return nil, fmt.Errorf("IDXGIOutput::GetDesc: %w", hr)
	}
	if desc.attached == 0 {
		return nil, nil
	}
	if desc.rotation > rotationIdentity {
		return nil, errors.New("a rotated monitor")
	}
	var output1 *comObject
	if hr := output.call(iunknownQueryInterface, uintptr(unsafe.Pointer(&iidIDXGIOutput1)), uintptr(unsafe.Pointer(&output1))); hr != nil {
		return nil, fmt.Errorf("IDXGIOutput1: %w", hr)
	}
	defer output1.release()
	o := &dupOutput{x: int(desc.rect[0]) - vx, y: int(desc.rect[1]) - vy}
	if hr := hresult(procD3D11CreateDevice.Call(uintptr(unsafe.Pointer(adapter)), d3dDriverTypeUnknown, 0, d3d11CreateDeviceBGRASupport, 0, 0, d3d11SDKVersion,
		uintptr(unsafe.Pointer(&o.device)), 0, uintptr(unsafe.Pointer(&o.context)))); hr != nil {
		return nil, fmt.Errorf("D3D11CreateDevice: %w", hr)
	}
	if hr := output1.call(dxgiOutput1DuplicateOutput, uintptr(unsafe.Pointer(o.device)), uintptr(unsafe.Pointer(&o.dupl))); hr != nil {
		o.release()
		return nil, fmt.Errorf("DuplicateOutput: %w", hr)
	}
	var dd duplDesc
	o.dupl.call(duplGetDesc, uintptr(unsafe.Pointer(&dd)))
	o.w, o.h, o.inMemory = int(dd.width), int(dd.height), dd.inSystemMemory != 0
	if o.w <= 0 || o.h <= 0 {
		o.release()
		return nil, errors.New("a duplication without size")
	}
	o.img = make([]uint32, o.w*o.h)
	if !o.inMemory {
		td := texture2DDesc{width: uint32(o.w), height: uint32(o.h), mipLevels: 1, arraySize: 1, format: dxgiFormatB8G8R8A8,
			sampleCount: 1, usage: d3d11UsageStaging, cpuAccess: d3d11CPUAccessRead}
		if hr := o.device.call(d3d11DeviceCreateTexture2D, uintptr(unsafe.Pointer(&td)), 0, uintptr(unsafe.Pointer(&o.staging))); hr != nil {
			o.release()
			return nil, fmt.Errorf("CreateTexture2D: %w", hr)
		}
	}
	return o, nil
}

func (o *dupOutput) release() {
	o.staging.release()
	o.dupl.release()
	o.context.release()
	o.device.release()
	o.staging, o.dupl, o.context, o.device = nil, nil, nil, nil
}

// frameInfo is DXGI_OUTDUPL_FRAME_INFO.
type frameInfo struct {
	lastPresent, lastMouse int64
	accumulated            uint32
	coalesced, masked      int32
	pointerX, pointerY     int32
	pointerVisible         int32
	metadataSize           uint32
	pointerShapeSize       uint32
}

// texture2DDesc is D3D11_TEXTURE2D_DESC.
type texture2DDesc struct {
	width, height, mipLevels, arraySize, format uint32
	sampleCount, sampleQuality                  uint32
	usage, bindFlags, cpuAccess, miscFlags      uint32
}

// mappedSubresource is D3D11_MAPPED_SUBRESOURCE; mappedRect DXGI_MAPPED_RECT.
type mappedSubresource struct {
	data                 unsafe.Pointer
	rowPitch, depthPitch uint32
}

type mappedRect struct {
	pitch int32
	bits  unsafe.Pointer
}

// acquire takes the output's next frame, if there is one, into img. It
// returns what changed, in the output's pixels: nil when it cannot tell (the
// first frame), empty when nothing did.
func (o *dupOutput) acquire() ([]image.Rectangle, error) {
	var info frameInfo
	var res *comObject
	hr := o.dupl.call(duplAcquireNextFrame, 0, uintptr(unsafe.Pointer(&info)), uintptr(unsafe.Pointer(&res)))
	if errors.Is(hr, errWaitTimeout) {
		return []image.Rectangle{}, nil
	}
	if hr != nil {
		return nil, fmt.Errorf("AcquireNextFrame: %w", hr)
	}
	defer o.dupl.call(duplReleaseFrame)
	defer res.release()
	if info.lastPresent == 0 {
		return []image.Rectangle{}, nil // only the pointer moved
	}
	rects, rectsOK := o.changes(info.metadataSize)
	if err := o.read(res); err != nil {
		return nil, err
	}
	whole := []image.Rectangle{image.Rect(0, 0, o.w, o.h)}
	if !o.fresh {
		o.fresh = true
		return whole, nil
	}
	if !rectsOK {
		return whole, nil
	}
	return rects, nil
}

// changes reads the frame's moved and dirty rectangles.
func (o *dupOutput) changes(size uint32) ([]image.Rectangle, bool) {
	if size == 0 {
		return []image.Rectangle{}, true
	}
	if uint32(len(o.meta)) < size {
		o.meta = make([]byte, size)
	}
	var rects []image.Rectangle
	var n uint32
	if hr := o.dupl.call(duplGetFrameMoveRects, uintptr(len(o.meta)), uintptr(unsafe.Pointer(&o.meta[0])), uintptr(unsafe.Pointer(&n))); hr != nil {
		return nil, false
	}
	const moveSize = 24 // POINT + RECT
	for i := uint32(0); i+moveSize <= n; i += moveSize {
		r := (*[6]int32)(unsafe.Pointer(&o.meta[i]))
		rects = append(rects, image.Rect(int(r[2]), int(r[3]), int(r[4]), int(r[5])))
	}
	if hr := o.dupl.call(duplGetFrameDirtyRects, uintptr(len(o.meta)), uintptr(unsafe.Pointer(&o.meta[0])), uintptr(unsafe.Pointer(&n))); hr != nil {
		return nil, false
	}
	for i := uint32(0); i+16 <= n; i += 16 {
		r := (*[4]int32)(unsafe.Pointer(&o.meta[i]))
		rects = append(rects, image.Rect(int(r[0]), int(r[1]), int(r[2]), int(r[3])))
	}
	return rects, true
}

// read copies the frame's image into img.
func (o *dupOutput) read(res *comObject) error {
	if o.inMemory {
		var m mappedRect
		if hr := o.dupl.call(duplMapDesktopSurface, uintptr(unsafe.Pointer(&m))); hr != nil {
			return fmt.Errorf("MapDesktopSurface: %w", hr)
		}
		o.copyRows(m.bits, int(m.pitch))
		o.dupl.call(duplUnMapDesktopSurface)
		return nil
	}
	var tex *comObject
	if hr := res.call(iunknownQueryInterface, uintptr(unsafe.Pointer(&iidID3D11Texture2D)), uintptr(unsafe.Pointer(&tex))); hr != nil {
		return fmt.Errorf("ID3D11Texture2D: %w", hr)
	}
	defer tex.release()
	o.context.call(d3d11ContextCopyResource, uintptr(unsafe.Pointer(o.staging)), uintptr(unsafe.Pointer(tex)))
	var m mappedSubresource
	if hr := o.context.call(d3d11ContextMap, uintptr(unsafe.Pointer(o.staging)), 0, d3d11MapRead, 0, uintptr(unsafe.Pointer(&m))); hr != nil {
		return fmt.Errorf("Map: %w", hr)
	}
	o.copyRows(m.data, int(m.rowPitch))
	o.context.call(d3d11ContextUnmap, uintptr(unsafe.Pointer(o.staging)), 0)
	return nil
}

// copyRows copies a mapped BGRA image with pitch bytes per row into img.
func (o *dupOutput) copyRows(bits unsafe.Pointer, pitch int) {
	if bits == nil || pitch < o.w*4 {
		return
	}
	for y := range o.h {
		row := unsafe.Slice((*uint32)(unsafe.Add(bits, y*pitch)), o.w)
		dst := o.img[y*o.w : (y+1)*o.w]
		for x, p := range row {
			dst[x] = p & 0x00ffffff
		}
	}
}

// comObject is a COM interface pointer: its first word points at the vtable.
// Only the slots this file knows are ever read.
type comObject struct {
	vtbl *[64]uintptr
}

// call calls method i of o and turns a failed HRESULT into an error.
func (o *comObject) call(i int, args ...uintptr) error {
	r, _, _ := syscall.SyscallN(o.vtbl[i], append([]uintptr{uintptr(unsafe.Pointer(o))}, args...)...)
	return hresult(r, 0, nil)
}

func (o *comObject) release() {
	if o != nil {
		o.call(iunknownRelease)
	}
}

// hresultError is a failed HRESULT.
type hresultError uint32

func (e hresultError) Error() string { return fmt.Sprintf("HRESULT 0x%08X", uint32(e)) }

var (
	errNotFound    error = hresultError(0x887A0002)
	errAccessLost  error = hresultError(0x887A0026)
	errWaitTimeout error = hresultError(0x887A0027)
)

// hresult takes a Proc.Call result and returns its HRESULT as an error, nil
// on success.
func hresult(r, _ uintptr, _ error) error {
	if int32(uint32(r)) < 0 {
		return hresultError(uint32(r))
	}
	return nil
}

var (
	d3d11                  = windows.NewLazySystemDLL("d3d11.dll")
	dxgi                   = windows.NewLazySystemDLL("dxgi.dll")
	procD3D11CreateDevice  = d3d11.NewProc("D3D11CreateDevice")
	procCreateDXGIFactory1 = dxgi.NewProc("CreateDXGIFactory1")

	iidIDXGIFactory1   = windows.GUID{Data1: 0x770aae78, Data2: 0xf26f, Data3: 0x4dba, Data4: [8]byte{0xa8, 0x29, 0x25, 0x3c, 0x83, 0xd1, 0xb3, 0x87}}
	iidIDXGIOutput1    = windows.GUID{Data1: 0x00cddea8, Data2: 0x939b, Data3: 0x4b83, Data4: [8]byte{0xa3, 0x40, 0xa6, 0x85, 0x22, 0x66, 0x66, 0xcc}}
	iidID3D11Texture2D = windows.GUID{Data1: 0x6f15aaf2, Data2: 0xd208, Data3: 0x4e89, Data4: [8]byte{0x9a, 0xb4, 0x48, 0x95, 0x35, 0xd3, 0x4f, 0x9c}}
)

// Vtable slots (from the interfaces' declaration order) and constants.
const (
	iunknownQueryInterface     = 0
	iunknownRelease            = 2
	dxgiFactory1EnumAdapters1  = 12
	dxgiAdapterEnumOutputs     = 7
	dxgiOutputGetDesc          = 7
	dxgiOutput1DuplicateOutput = 22
	duplGetDesc                = 7
	duplAcquireNextFrame       = 8
	duplGetFrameDirtyRects     = 9
	duplGetFrameMoveRects      = 10
	duplMapDesktopSurface      = 12
	duplUnMapDesktopSurface    = 13
	duplReleaseFrame           = 14
	d3d11DeviceCreateTexture2D = 5
	d3d11ContextMap            = 14
	d3d11ContextUnmap          = 15
	d3d11ContextCopyResource   = 47

	d3dDriverTypeUnknown         = 0
	d3d11CreateDeviceBGRASupport = 0x20
	d3d11SDKVersion              = 7
	d3d11UsageStaging            = 3
	d3d11CPUAccessRead           = 0x20000
	d3d11MapRead                 = 1
	dxgiFormatB8G8R8A8           = 87
	rotationIdentity             = 1 // DXGI_MODE_ROTATION_IDENTITY; 0 is unspecified
)
