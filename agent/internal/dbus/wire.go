// Package dbus is a small D-Bus client (ADR-0031 §2): just what the agent needs
// to talk to the desktop portals and the permission store on the user's
// session bus. It authenticates with EXTERNAL, passes file descriptors, makes
// method calls and receives signals; it does not export objects.
package dbus

import (
	"encoding/binary"
	"errors"
	"fmt"
	"math"
	"sort"
)

// ObjectPath is a D-Bus object path (type "o").
type ObjectPath string

// Signature is a D-Bus type signature (type "g").
type Signature string

// FD is a file descriptor to send (type "h"). Received ones arrive as
// FDIndex, an index into the message's Files.
type FD int

// FDIndex is a received "h": the index of the descriptor in Message.Files.
type FDIndex uint32

// Variant is a value with its own signature (type "v").
type Variant struct {
	Sig   string
	Value any
}

// MakeVariant wraps v in a Variant, guessing the signature from its Go type.
// It covers the basic types and map[string]Variant (a{sv}).
func MakeVariant(v any) Variant {
	return Variant{Sig: guessSig(v), Value: v}
}

func guessSig(v any) string {
	switch v.(type) {
	case uint8:
		return "y"
	case bool:
		return "b"
	case int16:
		return "n"
	case uint16:
		return "q"
	case int32:
		return "i"
	case uint32:
		return "u"
	case int64:
		return "x"
	case uint64:
		return "t"
	case float64:
		return "d"
	case string:
		return "s"
	case ObjectPath:
		return "o"
	case Signature:
		return "g"
	case FD:
		return "h"
	case []string:
		return "as"
	case []byte:
		return "ay"
	case map[string]Variant:
		return "a{sv}"
	case Variant:
		return "v"
	}
	return ""
}

// byteOrderer is binary.LittleEndian or binary.BigEndian.
type byteOrderer interface {
	binary.ByteOrder
	binary.AppendByteOrder
}

// encoder appends values to a message body or header.
type encoder struct {
	buf   []byte
	order byteOrderer
	fds   []int
}

func (e *encoder) align(n int) {
	for len(e.buf)%n != 0 {
		e.buf = append(e.buf, 0)
	}
}

func (e *encoder) u32(v uint32) {
	e.align(4)
	e.buf = e.order.AppendUint32(e.buf, v)
}

// encode appends v as the single complete type sig.
func (e *encoder) encode(sig string, v any) error {
	if sig == "" {
		return errors.New("dbus: empty signature")
	}
	switch sig[0] {
	case 'y':
		b, ok := v.(uint8)
		if !ok {
			return typeErr(sig, v)
		}
		e.buf = append(e.buf, b)
	case 'b':
		b, ok := v.(bool)
		if !ok {
			return typeErr(sig, v)
		}
		var n uint32
		if b {
			n = 1
		}
		e.u32(n)
	case 'n', 'q':
		var n uint16
		switch x := v.(type) {
		case int16:
			n = uint16(x)
		case uint16:
			n = x
		default:
			return typeErr(sig, v)
		}
		e.align(2)
		e.buf = e.order.AppendUint16(e.buf, n)
	case 'i', 'u':
		var n uint32
		switch x := v.(type) {
		case int32:
			n = uint32(x)
		case uint32:
			n = x
		case int:
			n = uint32(int32(x))
		default:
			return typeErr(sig, v)
		}
		e.u32(n)
	case 'h':
		fd, ok := v.(FD)
		if !ok {
			return typeErr(sig, v)
		}
		e.u32(uint32(len(e.fds)))
		e.fds = append(e.fds, int(fd))
	case 'x', 't':
		var n uint64
		switch x := v.(type) {
		case int64:
			n = uint64(x)
		case uint64:
			n = x
		default:
			return typeErr(sig, v)
		}
		e.align(8)
		e.buf = e.order.AppendUint64(e.buf, n)
	case 'd':
		f, ok := v.(float64)
		if !ok {
			return typeErr(sig, v)
		}
		e.align(8)
		e.buf = e.order.AppendUint64(e.buf, math.Float64bits(f))
	case 's', 'o':
		var s string
		switch x := v.(type) {
		case string:
			s = x
		case ObjectPath:
			s = string(x)
		default:
			return typeErr(sig, v)
		}
		e.u32(uint32(len(s)))
		e.buf = append(e.buf, s...)
		e.buf = append(e.buf, 0)
	case 'g':
		var s string
		switch x := v.(type) {
		case string:
			s = x
		case Signature:
			s = string(x)
		default:
			return typeErr(sig, v)
		}
		if len(s) > 255 {
			return errors.New("dbus: signature too long")
		}
		e.buf = append(e.buf, byte(len(s)))
		e.buf = append(e.buf, s...)
		e.buf = append(e.buf, 0)
	case 'v':
		vr, ok := v.(Variant)
		if !ok {
			vr = MakeVariant(v)
		}
		if vr.Sig == "" {
			return fmt.Errorf("dbus: no signature for a variant of %T", vr.Value)
		}
		if err := e.encode("g", vr.Sig); err != nil {
			return err
		}
		return e.encode(vr.Sig, vr.Value)
	case '(':
		fields, ok := v.([]any)
		if !ok {
			return typeErr(sig, v)
		}
		inner := sig[1 : len(sig)-1]
		e.align(8)
		for _, f := range fields {
			one, rest, err := next(inner)
			if err != nil {
				return err
			}
			if err := e.encode(one, f); err != nil {
				return err
			}
			inner = rest
		}
		if inner != "" {
			return fmt.Errorf("dbus: too few fields for %s", sig)
		}
	case 'a':
		return e.array(sig, v)
	default:
		return fmt.Errorf("dbus: unsupported type %q", sig)
	}
	return nil
}

func (e *encoder) array(sig string, v any) error {
	elem := sig[1:]
	e.u32(0)
	lenAt := len(e.buf) - 4
	e.align(alignOf(elem[0]))
	start := len(e.buf)
	var err error
	switch x := v.(type) {
	case []byte:
		if elem != "y" {
			return typeErr(sig, v)
		}
		e.buf = append(e.buf, x...)
	case []string:
		for _, s := range x {
			if err = e.encode(elem, s); err != nil {
				return err
			}
		}
	case []uint32:
		for _, n := range x {
			if err = e.encode(elem, n); err != nil {
				return err
			}
		}
	case []any:
		for _, item := range x {
			if err = e.encode(elem, item); err != nil {
				return err
			}
		}
	case map[string]Variant:
		if elem != "{sv}" {
			return typeErr(sig, v)
		}
		keys := make([]string, 0, len(x))
		for k := range x {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			e.align(8)
			if err = e.encode("s", k); err != nil {
				return err
			}
			if err = e.encode("v", x[k]); err != nil {
				return err
			}
		}
	case map[string][]string:
		if elem != "{sas}" {
			return typeErr(sig, v)
		}
		keys := make([]string, 0, len(x))
		for k := range x {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			e.align(8)
			if err = e.encode("s", k); err != nil {
				return err
			}
			if err = e.encode("as", x[k]); err != nil {
				return err
			}
		}
	default:
		return typeErr(sig, v)
	}
	e.order.PutUint32(e.buf[lenAt:], uint32(len(e.buf)-start))
	return nil
}

func typeErr(sig string, v any) error {
	return fmt.Errorf("dbus: cannot encode %T as %q", v, sig)
}

func alignOf(c byte) int {
	switch c {
	case 'n', 'q':
		return 2
	case 'b', 'i', 'u', 'h', 's', 'o', 'a':
		return 4
	case 'x', 't', 'd', '(', '{':
		return 8
	}
	return 1
}

// next splits the first complete type off sig.
func next(sig string) (string, string, error) {
	if sig == "" {
		return "", "", errors.New("dbus: missing type in signature")
	}
	switch sig[0] {
	case 'a':
		one, rest, err := next(sig[1:])
		if err != nil {
			return "", "", err
		}
		return "a" + one, rest, nil
	case '(', '{':
		closer := byte(')')
		if sig[0] == '{' {
			closer = '}'
		}
		depth := 0
		for i := 0; i < len(sig); i++ {
			switch sig[i] {
			case '(', '{':
				depth++
			case ')', '}':
				depth--
				if depth == 0 {
					if sig[i] != closer {
						return "", "", fmt.Errorf("dbus: bad signature %q", sig)
					}
					return sig[:i+1], sig[i+1:], nil
				}
			}
		}
		return "", "", fmt.Errorf("dbus: unbalanced signature %q", sig)
	}
	return sig[:1], sig[1:], nil
}

// splitSig splits a signature into its complete types.
func splitSig(sig string) ([]string, error) {
	var out []string
	for sig != "" {
		one, rest, err := next(sig)
		if err != nil {
			return nil, err
		}
		out = append(out, one)
		sig = rest
	}
	return out, nil
}

// decoder reads values from a message.
type decoder struct {
	buf   []byte
	pos   int
	order byteOrderer
	depth int
}

var errShort = errors.New("dbus: message too short")

func (d *decoder) align(n int) error {
	p := (d.pos + n - 1) / n * n
	if p > len(d.buf) {
		return errShort
	}
	d.pos = p
	return nil
}

func (d *decoder) take(n int) ([]byte, error) {
	if n < 0 || d.pos+n > len(d.buf) {
		return nil, errShort
	}
	b := d.buf[d.pos : d.pos+n]
	d.pos += n
	return b, nil
}

func (d *decoder) u32() (uint32, error) {
	if err := d.align(4); err != nil {
		return 0, err
	}
	b, err := d.take(4)
	if err != nil {
		return 0, err
	}
	return d.order.Uint32(b), nil
}

// decode reads one value of the complete type sig.
//
// Values come back as: y uint8, b bool, n int16, q uint16, i int32, u uint32,
// x int64, t uint64, d float64, s string, o ObjectPath, g Signature,
// h FDIndex, v Variant, structs []any, ay []byte, arrays []any, and dicts
// map[string]any (string keys) or map[any]any.
func (d *decoder) decode(sig string) (any, error) {
	d.depth++
	defer func() { d.depth-- }()
	if d.depth > 64 {
		return nil, errors.New("dbus: value nested too deep")
	}
	switch sig[0] {
	case 'y':
		b, err := d.take(1)
		if err != nil {
			return nil, err
		}
		return b[0], nil
	case 'b':
		n, err := d.u32()
		return n != 0, err
	case 'n', 'q':
		if err := d.align(2); err != nil {
			return nil, err
		}
		b, err := d.take(2)
		if err != nil {
			return nil, err
		}
		if sig[0] == 'n' {
			return int16(d.order.Uint16(b)), nil
		}
		return d.order.Uint16(b), nil
	case 'i':
		n, err := d.u32()
		return int32(n), err
	case 'u':
		return d.u32()
	case 'h':
		n, err := d.u32()
		return FDIndex(n), err
	case 'x', 't', 'd':
		if err := d.align(8); err != nil {
			return nil, err
		}
		b, err := d.take(8)
		if err != nil {
			return nil, err
		}
		n := d.order.Uint64(b)
		switch sig[0] {
		case 'x':
			return int64(n), nil
		case 't':
			return n, nil
		}
		return math.Float64frombits(n), nil
	case 's', 'o':
		n, err := d.u32()
		if err != nil {
			return nil, err
		}
		b, err := d.take(int(n) + 1)
		if err != nil {
			return nil, err
		}
		s := string(b[:n])
		if sig[0] == 'o' {
			return ObjectPath(s), nil
		}
		return s, nil
	case 'g':
		s, err := d.sig()
		return Signature(s), err
	case 'v':
		s, err := d.sig()
		if err != nil {
			return nil, err
		}
		if one, rest, err := next(s); err != nil || rest != "" || one == "" {
			return nil, fmt.Errorf("dbus: bad variant signature %q", s)
		}
		v, err := d.decode(s)
		return Variant{Sig: s, Value: v}, err
	case '(', '{':
		if err := d.align(8); err != nil {
			return nil, err
		}
		parts, err := splitSig(sig[1 : len(sig)-1])
		if err != nil {
			return nil, err
		}
		out := make([]any, 0, len(parts))
		for _, p := range parts {
			v, err := d.decode(p)
			if err != nil {
				return nil, err
			}
			out = append(out, v)
		}
		return out, nil
	case 'a':
		return d.array(sig[1:])
	}
	return nil, fmt.Errorf("dbus: unsupported type %q", sig)
}

func (d *decoder) sig() (string, error) {
	b, err := d.take(1)
	if err != nil {
		return "", err
	}
	s, err := d.take(int(b[0]) + 1)
	if err != nil {
		return "", err
	}
	return string(s[:b[0]]), nil
}

func (d *decoder) array(elem string) (any, error) {
	n, err := d.u32()
	if err != nil {
		return nil, err
	}
	if n > 64<<20 {
		return nil, errors.New("dbus: array too long")
	}
	if err := d.align(alignOf(elem[0])); err != nil {
		return nil, err
	}
	end := d.pos + int(n)
	if end > len(d.buf) {
		return nil, errShort
	}
	if elem == "y" {
		b, _ := d.take(int(n))
		return append([]byte(nil), b...), nil
	}
	if elem[0] == '{' {
		parts, err := splitSig(elem[1 : len(elem)-1])
		if err != nil || len(parts) != 2 {
			return nil, fmt.Errorf("dbus: bad dict signature %q", elem)
		}
		strKeys := parts[0] == "s"
		sm := map[string]any{}
		am := map[any]any{}
		for d.pos < end {
			kv, err := d.decode(elem)
			if err != nil {
				return nil, err
			}
			pair := kv.([]any)
			if strKeys {
				sm[pair[0].(string)] = pair[1]
			} else {
				am[pair[0]] = pair[1]
			}
		}
		if strKeys {
			return sm, nil
		}
		return am, nil
	}
	var out []any
	for d.pos < end {
		v, err := d.decode(elem)
		if err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	if d.pos != end {
		return nil, errors.New("dbus: array overruns its length")
	}
	return out, nil
}
