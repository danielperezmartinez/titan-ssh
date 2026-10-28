package x11

import "errors"

// XInput device uses (XIDeviceInfo.use).
const (
	XIMasterPointer = 1
	XISlavePointer  = 3
)

// XInput class types and scroll directions.
const (
	xiScrollClass      = 3
	XIScrollVertical   = 1
	XIScrollHorizontal = 2
)

// ScrollAxis is a valuator of a device that scrolls, as a touchpad or a
// high-resolution wheel has (XI 2.1 smooth scrolling).
type ScrollAxis struct {
	Number    int     // the valuator
	Type      int     // XIScrollVertical or XIScrollHorizontal
	Increment float64 // valuator units per wheel notch
}

// InputDevice is an XInput device and its scroll axes.
type InputDevice struct {
	ID      uint16
	Use     int
	Enabled bool
	Name    string
	Scroll  []ScrollAxis
}

// XInputInit announces XI 2.2, which the server requires before any other
// XI2 request.
func (x *Conn) XInputInit() error {
	op, err := x.extension("XInputExtension")
	if err != nil {
		return err
	}
	req := request(8, op, 47) // XIQueryVersion
	le.PutUint16(req[4:], 2)
	le.PutUint16(req[6:], 2)
	reply, err := x.roundTrip(req)
	if err != nil {
		return err
	}
	if major, minor := le.Uint16(reply[8:]), le.Uint16(reply[10:]); major < 2 || (major == 2 && minor < 1) {
		return errors.New("x11: XInput is older than 2.1")
	}
	return nil
}

// InputDevices lists every XInput device with its scroll axes. Call
// XInputInit first.
func (x *Conn) InputDevices() ([]InputDevice, error) {
	op, err := x.extension("XInputExtension")
	if err != nil {
		return nil, err
	}
	req := request(8, op, 48) // XIQueryDevice
	// deviceid 0: XIAllDevices
	reply, err := x.roundTrip(req)
	if err != nil {
		return nil, err
	}
	return parseInputDevices(reply)
}

func parseInputDevices(reply []byte) ([]InputDevice, error) {
	short := errors.New("x11: short XIQueryDevice reply")
	if len(reply) < 32 {
		return nil, short
	}
	n := int(le.Uint16(reply[8:]))
	b := reply[32:]
	devices := make([]InputDevice, 0, n)
	for range n {
		if len(b) < 12 {
			return nil, short
		}
		d := InputDevice{ID: le.Uint16(b), Use: int(le.Uint16(b[2:])), Enabled: b[10] != 0}
		classes, nameLen := int(le.Uint16(b[6:])), int(le.Uint16(b[8:]))
		b = b[12:]
		if len(b) < nameLen+pad4(nameLen) {
			return nil, short
		}
		d.Name = string(b[:nameLen])
		b = b[nameLen+pad4(nameLen):]
		for range classes {
			if len(b) < 4 {
				return nil, short
			}
			size := int(le.Uint16(b[2:])) * 4
			if size < 4 || len(b) < size {
				return nil, short
			}
			if le.Uint16(b) == xiScrollClass && size >= 24 {
				inc := float64(int32(le.Uint32(b[16:]))) + float64(le.Uint32(b[20:]))/(1<<32)
				d.Scroll = append(d.Scroll, ScrollAxis{Number: int(le.Uint16(b[6:])), Type: int(le.Uint16(b[8:])), Increment: inc})
			}
			b = b[size:]
		}
		devices = append(devices, d)
	}
	return devices, nil
}

// FakeDeviceMotion moves the valuators of device by deltas, starting at
// valuator first (at most 6), through XTEST as if the device itself had
// moved: the server then reports them as XI2 motion, with smooth scrolling
// on scroll axes and the wheel buttons it emulates from them for older
// clients. Call XTestInit first.
func (x *Conn) FakeDeviceMotion(device uint8, first int, deltas []int32) error {
	if len(deltas) == 0 || len(deltas) > 6 {
		return errors.New("x11: 1 to 6 valuators per motion")
	}
	op, err := x.extension("XTEST")
	if err != nil {
		return err
	}
	xi, err := x.QueryExtension("XInputExtension")
	if err != nil {
		return err
	}
	if !xi.Present {
		return errors.New("x11: the server has no XInputExtension")
	}
	req := request(4+32*2, op, 2) // FakeInput: DeviceMotionNotify + DeviceValuator
	req[4] = xi.FirstEvent + 5    // DeviceMotionNotify
	req[5] = 1                    // relative
	req[35] = device
	v := req[36:]
	v[0] = xi.FirstEvent // DeviceValuator
	v[1] = device
	v[6] = uint8(len(deltas))
	v[7] = uint8(first)
	for i, d := range deltas {
		le.PutUint32(v[8+i*4:], uint32(d))
	}
	return x.sendOnly(req)
}
