package x11

import (
	"io"
	"net"
	"testing"
)

// xiDevice encodes one XIDeviceInfo with its classes.
func xiDevice(id, use uint16, name string, classes ...[]byte) []byte {
	b := make([]byte, 12)
	le.PutUint16(b, id)
	le.PutUint16(b[2:], use)
	le.PutUint16(b[6:], uint16(len(classes)))
	le.PutUint16(b[8:], uint16(len(name)))
	b[10] = 1 // enabled
	b = append(b, name...)
	b = append(b, make([]byte, pad4(len(name)))...)
	for _, c := range classes {
		b = append(b, c...)
	}
	return b
}

// xiScroll encodes an XIScrollClass of valuator number.
func xiScroll(number, kind uint16, integral int32, frac uint32) []byte {
	c := make([]byte, 24)
	le.PutUint16(c, xiScrollClass)
	le.PutUint16(c[2:], 6)
	le.PutUint16(c[6:], number)
	le.PutUint16(c[8:], kind)
	le.PutUint32(c[16:], uint32(integral))
	le.PutUint32(c[20:], frac)
	return c
}

func TestParseInputDevices(t *testing.T) {
	valuator := make([]byte, 44) // an XIValuatorClass, skipped
	le.PutUint16(valuator, 2)
	le.PutUint16(valuator[2:], 11)
	reply := make([]byte, 32)
	reply[0] = 1
	le.PutUint16(reply[8:], 2)
	reply = append(reply, xiDevice(2, XIMasterPointer, "Virtual core pointer")...)
	reply = append(reply, xiDevice(11, XISlavePointer, "Touchpad", valuator,
		xiScroll(2, XIScrollHorizontal, 15, 0), xiScroll(3, XIScrollVertical, 15, 1<<31))...)
	devices, err := parseInputDevices(reply)
	if err != nil {
		t.Fatal(err)
	}
	if len(devices) != 2 || devices[0].Use != XIMasterPointer || len(devices[0].Scroll) != 0 {
		t.Fatalf("devices = %+v", devices)
	}
	d := devices[1]
	if d.ID != 11 || d.Name != "Touchpad" || !d.Enabled || len(d.Scroll) != 2 {
		t.Fatalf("touchpad = %+v", d)
	}
	if v := d.Scroll[1]; v.Number != 3 || v.Type != XIScrollVertical || v.Increment != 15.5 {
		t.Errorf("vertical axis = %+v", v)
	}
	if _, err := parseInputDevices(reply[:len(reply)-4]); err == nil {
		t.Error("a short reply parsed")
	}
}

func TestFakeDeviceMotionBytes(t *testing.T) {
	cli, srv := net.Pipe()
	defer srv.Close()
	got := make(chan []byte, 1)
	go func() {
		if !fakeSetup(t, srv, "cookie") {
			return
		}
		req := make([]byte, 68)
		if _, err := io.ReadFull(srv, req); err != nil {
			t.Error(err)
		}
		got <- req
	}()
	x, err := NewConn(cli, "MIT-MAGIC-COOKIE-1", []byte("cookie"))
	if err != nil {
		t.Fatal(err)
	}
	defer x.Close()
	x.exts["XTEST"] = Extension{Present: true, Opcode: 140}
	x.exts["XInputExtension"] = Extension{Present: true, Opcode: 131, FirstEvent: 66}
	if err := x.FakeDeviceMotion(11, 0, []int32{0, 0, 0, -3}); err != nil {
		t.Fatal(err)
	}
	req := <-got
	if req[0] != 140 || req[1] != 2 || le.Uint16(req[2:]) != 17 {
		t.Errorf("header % x", req[:4])
	}
	// DeviceMotionNotify, relative, from device 11.
	if req[4] != 66+5 || req[5] != 1 || req[35] != 11 {
		t.Errorf("motion % x", req[4:36])
	}
	// DeviceValuator: 4 valuators from 0, the last -3.
	v := req[36:]
	if v[0] != 66 || v[1] != 11 || v[6] != 4 || v[7] != 0 || int32(le.Uint32(v[20:])) != -3 {
		t.Errorf("valuators % x", v)
	}
}
