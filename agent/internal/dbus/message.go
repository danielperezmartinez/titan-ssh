package dbus

import (
	"encoding/binary"
	"errors"
	"fmt"
)

// Message types.
const (
	TypeMethodCall   = 1
	TypeMethodReturn = 2
	TypeError        = 3
	TypeSignal       = 4
)

// Header flags.
const (
	flagNoReplyExpected   = 0x1
	flagAllowInteractAuth = 0x4
)

// Header field codes.
const (
	fieldPath        = 1
	fieldInterface   = 2
	fieldMember      = 3
	fieldErrorName   = 4
	fieldReplySerial = 5
	fieldDestination = 6
	fieldSender      = 7
	fieldSignature   = 8
	fieldUnixFDs     = 9
)

// maxMessage bounds a message (the spec's limit is 128 MiB; nothing the agent
// receives comes near it).
const maxMessage = 16 << 20

// Message is one D-Bus message.
type Message struct {
	Type        byte
	Flags       byte
	Serial      uint32
	ReplySerial uint32
	Path        ObjectPath
	Interface   string
	Member      string
	ErrorName   string
	Destination string
	Sender      string
	Signature   string
	// Body holds the decoded arguments (on receive) or the values to send.
	Body []any
	// Files are the descriptors that came with the message, in the order the
	// "h" values index them. The receiver owns and closes them.
	Files []int
}

// Error is a D-Bus error reply.
type Error struct {
	Name    string
	Message string
}

func (e *Error) Error() string {
	if e.Message == "" {
		return e.Name
	}
	return e.Name + ": " + e.Message
}

// marshal encodes m (little-endian) and returns its bytes and descriptors.
func (m *Message) marshal() ([]byte, []int, error) {
	body := &encoder{order: binary.LittleEndian}
	if m.Signature != "" {
		types, err := splitSig(m.Signature)
		if err != nil {
			return nil, nil, err
		}
		if len(types) != len(m.Body) {
			return nil, nil, fmt.Errorf("dbus: %d values for signature %q", len(m.Body), m.Signature)
		}
		for i, t := range types {
			if err := body.encode(t, m.Body[i]); err != nil {
				return nil, nil, err
			}
		}
	}

	var fields []any
	add := func(code byte, sig string, v any) {
		fields = append(fields, []any{code, Variant{Sig: sig, Value: v}})
	}
	if m.Path != "" {
		add(fieldPath, "o", m.Path)
	}
	if m.Interface != "" {
		add(fieldInterface, "s", m.Interface)
	}
	if m.Member != "" {
		add(fieldMember, "s", m.Member)
	}
	if m.ErrorName != "" {
		add(fieldErrorName, "s", m.ErrorName)
	}
	if m.ReplySerial != 0 {
		add(fieldReplySerial, "u", m.ReplySerial)
	}
	if m.Destination != "" {
		add(fieldDestination, "s", m.Destination)
	}
	if m.Signature != "" {
		add(fieldSignature, "g", Signature(m.Signature))
	}
	if len(body.fds) > 0 {
		add(fieldUnixFDs, "u", uint32(len(body.fds)))
	}

	h := &encoder{order: binary.LittleEndian}
	h.buf = append(h.buf, 'l', m.Type, m.Flags, 1)
	h.u32(uint32(len(body.buf)))
	h.u32(m.Serial)
	if err := h.encode("a(yv)", fields); err != nil {
		return nil, nil, err
	}
	h.align(8)
	return append(h.buf, body.buf...), body.fds, nil
}

// frameLength tells how long the message starting at b is, once its first 16
// bytes are there; 0 means more bytes are needed.
func frameLength(b []byte) (int, error) {
	if len(b) < 16 {
		return 0, nil
	}
	order, err := byteOrder(b[0])
	if err != nil {
		return 0, err
	}
	bodyLen := int(order.Uint32(b[4:]))
	fieldsLen := int(order.Uint32(b[12:]))
	if bodyLen > maxMessage || fieldsLen > maxMessage {
		return 0, errors.New("dbus: message too large")
	}
	headerEnd := (16 + fieldsLen + 7) / 8 * 8
	return headerEnd + bodyLen, nil
}

func byteOrder(c byte) (byteOrderer, error) {
	switch c {
	case 'l':
		return binary.LittleEndian, nil
	case 'B':
		return binary.BigEndian, nil
	}
	return nil, fmt.Errorf("dbus: bad endianness byte %q", c)
}

// unmarshal decodes one whole message; fds are the descriptors received with
// it, of which it takes as many as its header says. It returns how many it
// took.
func unmarshal(b []byte, fds []int) (*Message, int, error) {
	order, err := byteOrder(b[0])
	if err != nil {
		return nil, 0, err
	}
	m := &Message{Type: b[1], Flags: b[2], Serial: order.Uint32(b[8:])}
	if b[3] != 1 {
		return nil, 0, fmt.Errorf("dbus: protocol version %d", b[3])
	}
	bodyLen := int(order.Uint32(b[4:]))
	d := &decoder{buf: b, pos: 12, order: order}
	raw, err := d.decode("a(yv)")
	if err != nil {
		return nil, 0, err
	}
	var nfds int
	for _, f := range raw.([]any) {
		pair := f.([]any)
		v := pair[1].(Variant).Value
		switch pair[0].(uint8) {
		case fieldPath:
			m.Path, _ = v.(ObjectPath)
		case fieldInterface:
			m.Interface, _ = v.(string)
		case fieldMember:
			m.Member, _ = v.(string)
		case fieldErrorName:
			m.ErrorName, _ = v.(string)
		case fieldReplySerial:
			m.ReplySerial, _ = v.(uint32)
		case fieldDestination:
			m.Destination, _ = v.(string)
		case fieldSender:
			m.Sender, _ = v.(string)
		case fieldSignature:
			s, _ := v.(Signature)
			m.Signature = string(s)
		case fieldUnixFDs:
			n, _ := v.(uint32)
			nfds = int(n)
		}
	}
	if nfds > len(fds) {
		return nil, 0, fmt.Errorf("dbus: message announces %d descriptors, %d arrived", nfds, len(fds))
	}
	m.Files = append([]int(nil), fds[:nfds]...)
	if err := d.align(8); err != nil {
		return nil, nfds, err
	}
	body := &decoder{buf: b[d.pos : d.pos+bodyLen], order: order}
	if m.Signature != "" {
		types, err := splitSig(m.Signature)
		if err != nil {
			return nil, nfds, err
		}
		for _, t := range types {
			v, err := body.decode(t)
			if err != nil {
				return nil, nfds, fmt.Errorf("dbus: %s.%s body: %w", m.Interface, m.Member, err)
			}
			m.Body = append(m.Body, v)
		}
	}
	return m, nfds, nil
}
