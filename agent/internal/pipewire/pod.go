// Package pipewire is a small PipeWire client in pure Go (ADR-0031 §2): just
// enough of the native protocol to consume the video streams of a ScreenCast
// portal session. It speaks the protocol over the portal's descriptor,
// exports one client-node with an input port per stream, takes the frames
// from memfd/SHM buffers when the graph signals the node's eventfd, and
// reads the cursor and damage metadata.
package pipewire

import (
	"encoding/binary"
	"errors"
	"fmt"
	"math"
)

// SPA POD types (spa/utils/type.h).
const (
	podNone      = 1
	podBool      = 2
	podID        = 3
	podInt       = 4
	podLong      = 5
	podFloat     = 6
	podDouble    = 7
	podString    = 8
	podBytes     = 9
	podRectangle = 10
	podFraction  = 11
	podBitmap    = 12
	podArray     = 13
	podStruct    = 14
	podObject    = 15
	podSequence  = 16
	podPointer   = 17
	podFd        = 18
	podChoice    = 19
	podPod       = 20
)

// Choice types.
const (
	choiceNone  = 0
	choiceRange = 1
	choiceStep  = 2
	choiceEnum  = 3
	choiceFlags = 4
)

var le = binary.LittleEndian

// pod is an encoded POD: its 8-byte header and body, padded to 8 bytes.
type pod []byte

func header(size, typ uint32) []byte {
	b := make([]byte, 8, 8+size+7)
	le.PutUint32(b, size)
	le.PutUint32(b[4:], typ)
	return b
}

func pad8(b []byte) []byte {
	for len(b)%8 != 0 {
		b = append(b, 0)
	}
	return b
}

func pInt(v int32) pod {
	b := header(4, podInt)
	b = le.AppendUint32(b, uint32(v))
	return pad8(b)
}

func pID(v uint32) pod {
	b := header(4, podID)
	b = le.AppendUint32(b, v)
	return pad8(b)
}

func pBool(v bool) pod {
	var n uint32
	if v {
		n = 1
	}
	b := header(4, podBool)
	b = le.AppendUint32(b, n)
	return pad8(b)
}

func pLong(v int64) pod {
	b := header(8, podLong)
	return le.AppendUint64(b, uint64(v))
}

func pString(s string) pod {
	b := header(uint32(len(s)+1), podString)
	b = append(b, s...)
	b = append(b, 0)
	return pad8(b)
}

func pNone() pod { return header(0, podNone) }

func pRect(w, h uint32) pod {
	b := header(8, podRectangle)
	b = le.AppendUint32(b, w)
	return le.AppendUint32(b, h)
}

func pFraction(num, denom uint32) pod {
	b := header(8, podFraction)
	b = le.AppendUint32(b, num)
	return le.AppendUint32(b, denom)
}

func pStruct(fields ...pod) pod {
	var body []byte
	for _, f := range fields {
		body = append(body, f...)
	}
	return append(header(uint32(len(body)), podStruct), body...)
}

// prop is one property of an object POD.
type prop struct {
	key, flags uint32
	value      pod
}

func pObject(typ, id uint32, props ...prop) pod {
	body := le.AppendUint32(nil, typ)
	body = le.AppendUint32(body, id)
	for _, p := range props {
		body = le.AppendUint32(body, p.key)
		body = le.AppendUint32(body, p.flags)
		body = append(body, p.value...)
	}
	return append(header(uint32(len(body)), podObject), body...)
}

// pChoice builds a choice of values of one fixed-size type (each value the
// body of a POD of that type: 4 bytes for Id and Int, 8 for Rectangle and
// Fraction).
func pChoice(kind, childType uint32, childSize int, values ...[]byte) pod {
	body := le.AppendUint32(nil, kind)
	body = le.AppendUint32(body, 0)
	body = le.AppendUint32(body, uint32(childSize))
	body = le.AppendUint32(body, childType)
	for _, v := range values {
		body = append(body, v...)
	}
	return pad8(append(header(uint32(len(body)), podChoice), body...))
}

func u32s(vs ...uint32) [][]byte {
	out := make([][]byte, len(vs))
	for i, v := range vs {
		out[i] = le.AppendUint32(nil, v)
	}
	return out
}

func pairs(vs ...[2]uint32) [][]byte {
	out := make([][]byte, len(vs))
	for i, v := range vs {
		out[i] = le.AppendUint32(le.AppendUint32(nil, v[0]), v[1])
	}
	return out
}

// dict encodes a spa_dict the way the protocol sends it: a struct of the
// count and the key/value strings.
func pDict(kv [][2]string) pod {
	fields := []pod{pInt(int32(len(kv)))}
	for _, p := range kv {
		fields = append(fields, pString(p[0]), pString(p[1]))
	}
	return pStruct(fields...)
}

// Parsing.

var errPod = errors.New("pipewire: malformed POD")

// value is a parsed POD: its type and its body (without padding).
type value struct {
	typ  uint32
	body []byte
}

// parsePod reads the POD at the start of b and returns it and what follows
// it (after padding).
func parsePod(b []byte) (value, []byte, error) {
	if len(b) < 8 {
		return value{}, nil, errPod
	}
	size := le.Uint32(b)
	typ := le.Uint32(b[4:])
	if uint64(size) > uint64(len(b)-8) {
		return value{}, nil, errPod
	}
	v := value{typ: typ, body: b[8 : 8+size]}
	next := (8 + int(size) + 7) &^ 7
	if next > len(b) {
		next = len(b)
	}
	return v, b[next:], nil
}

func (v value) int() (int32, error) {
	if (v.typ != podInt && v.typ != podID && v.typ != podBool) || len(v.body) < 4 {
		return 0, fmt.Errorf("pipewire: want an Int, got type %d", v.typ)
	}
	return int32(le.Uint32(v.body)), nil
}

func (v value) uint() (uint32, error) {
	n, err := v.int()
	return uint32(n), err
}

func (v value) long() (int64, error) {
	if (v.typ != podLong && v.typ != podFd) || len(v.body) < 8 {
		return 0, fmt.Errorf("pipewire: want a Long, got type %d", v.typ)
	}
	return int64(le.Uint64(v.body)), nil
}

func (v value) string() (string, error) {
	switch v.typ {
	case podNone:
		return "", nil
	case podString:
		if len(v.body) == 0 {
			return "", nil
		}
		return string(v.body[:len(v.body)-1]), nil
	}
	return "", fmt.Errorf("pipewire: want a String, got type %d", v.typ)
}

func (v value) double() float64 {
	if v.typ == podDouble && len(v.body) >= 8 {
		return math.Float64frombits(le.Uint64(v.body))
	}
	return 0
}

// fields returns the members of a Struct.
func (v value) fields() ([]value, error) {
	if v.typ != podStruct {
		return nil, fmt.Errorf("pipewire: want a Struct, got type %d", v.typ)
	}
	var out []value
	rest := v.body
	for len(rest) > 0 {
		f, r, err := parsePod(rest)
		if err != nil {
			return nil, err
		}
		out = append(out, f)
		rest = r
	}
	return out, nil
}

// object is a parsed Object POD.
type object struct {
	typ, id uint32
	props   map[uint32]value
}

func (v value) object() (object, error) {
	if v.typ != podObject || len(v.body) < 8 {
		return object{}, fmt.Errorf("pipewire: want an Object, got type %d", v.typ)
	}
	o := object{typ: le.Uint32(v.body), id: le.Uint32(v.body[4:]), props: map[uint32]value{}}
	rest := v.body[8:]
	for len(rest) >= 8 {
		key := le.Uint32(rest)
		val, r, err := parsePod(rest[8:])
		if err != nil {
			return object{}, err
		}
		o.props[key] = val
		rest = r
	}
	return o, nil
}

// fixed returns the value a property settles on: itself, or the default
// (first) value of a Choice.
func (v value) fixed() value {
	if v.typ != podChoice || len(v.body) < 16 {
		return v
	}
	size := le.Uint32(v.body[8:])
	typ := le.Uint32(v.body[12:])
	vals := v.body[16:]
	if uint32(len(vals)) < size {
		return value{typ: podNone}
	}
	return value{typ: typ, body: vals[:size]}
}

func (v value) pair() (uint32, uint32, error) {
	v = v.fixed()
	if (v.typ != podRectangle && v.typ != podFraction) || len(v.body) < 8 {
		return 0, 0, fmt.Errorf("pipewire: want a Rectangle, got type %d", v.typ)
	}
	return le.Uint32(v.body), le.Uint32(v.body[4:]), nil
}

// structArgs parses a message body that is one Struct and returns its
// members, checking there are at least n.
func structArgs(body []byte, n int) ([]value, error) {
	v, _, err := parsePod(body)
	if err != nil {
		return nil, err
	}
	f, err := v.fields()
	if err != nil {
		return nil, err
	}
	if len(f) < n {
		return nil, fmt.Errorf("pipewire: %d arguments, want %d", len(f), n)
	}
	return f, nil
}
