// Package protocol implements the titan-ssh level-3 agent wire protocol
// (ADR-0008). It is the exact mirror of the client-side codec in
// shared/src/commonMain/kotlin/io/github/danielperezmartinez/titanssh/terminal/AgentProtocol.kt —
// keep the two in lockstep.
//
// Frame layout (binary, big-endian, length-prefixed):
//
//	+--------+-----------------+-------------------+
//	| type   | length (u32 BE) | payload[length]   |
//	| 1 byte | 4 bytes         | length bytes      |
//	+--------+-----------------+-------------------+
package protocol

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
)

// MaxPayload bounds a single frame's payload to guard memory on malformed input.
const MaxPayload = 16 * 1024 * 1024

// headerSize is the fixed frame header: 1-byte type + 4-byte length.
const headerSize = 5

// Type is the one-byte wire code identifying a frame.
type Type uint8

const (
	TypeHello      Type = 1 // client -> agent
	TypeHelloOK    Type = 2 // agent -> client
	TypeData       Type = 3 // agent -> client
	TypeInput      Type = 4 // client -> agent
	TypeResize     Type = 5 // client -> agent
	TypeReplayFrom Type = 6 // client -> agent
	TypeAck        Type = 7 // client -> agent
	TypeBye        Type = 8 // either direction
)

// ErrUnknownType and ErrFrameTooLarge are protocol violations from a peer.
var (
	ErrUnknownType   = errors.New("protocol: unknown frame type")
	ErrFrameTooLarge = errors.New("protocol: frame length out of range")
)

// Frame is a decoded protocol frame. Exactly one of the typed fields is
// meaningful, per Type.
type Frame struct {
	Type Type

	// Hello
	SessionID  string
	LastOffset uint64
	// Hello / Resize
	Cols uint16
	Rows uint16
	// HelloOK
	HeadOffset uint64
	TailOffset uint64
	// Data
	Offset uint64
	// Data / Input
	Bytes []byte
	// ReplayFrom / Ack
	OffsetArg uint64
}

// Encode serializes f (header + payload) into a new byte slice.
func Encode(f Frame) []byte {
	payload := encodePayload(f)
	out := make([]byte, headerSize+len(payload))
	out[0] = byte(f.Type)
	binary.BigEndian.PutUint32(out[1:5], uint32(len(payload)))
	copy(out[headerSize:], payload)
	return out
}

func encodePayload(f Frame) []byte {
	switch f.Type {
	case TypeHello:
		sid := []byte(f.SessionID)
		buf := make([]byte, 2+len(sid)+8+2+2)
		binary.BigEndian.PutUint16(buf[0:2], uint16(len(sid)))
		p := 2 + copy(buf[2:], sid)
		binary.BigEndian.PutUint64(buf[p:], f.LastOffset)
		p += 8
		binary.BigEndian.PutUint16(buf[p:], f.Cols)
		p += 2
		binary.BigEndian.PutUint16(buf[p:], f.Rows)
		return buf
	case TypeHelloOK:
		buf := make([]byte, 16)
		binary.BigEndian.PutUint64(buf[0:8], f.HeadOffset)
		binary.BigEndian.PutUint64(buf[8:16], f.TailOffset)
		return buf
	case TypeData:
		buf := make([]byte, 8+len(f.Bytes))
		binary.BigEndian.PutUint64(buf[0:8], f.Offset)
		copy(buf[8:], f.Bytes)
		return buf
	case TypeInput:
		return append([]byte(nil), f.Bytes...)
	case TypeResize:
		buf := make([]byte, 4)
		binary.BigEndian.PutUint16(buf[0:2], f.Cols)
		binary.BigEndian.PutUint16(buf[2:4], f.Rows)
		return buf
	case TypeReplayFrom:
		buf := make([]byte, 8)
		binary.BigEndian.PutUint64(buf, f.OffsetArg)
		return buf
	case TypeAck:
		buf := make([]byte, 8)
		binary.BigEndian.PutUint64(buf, f.OffsetArg)
		return buf
	case TypeBye:
		return nil
	default:
		return nil
	}
}

func decodePayload(t Type, payload []byte) (Frame, error) {
	f := Frame{Type: t}
	switch t {
	case TypeHello:
		if len(payload) < 2 {
			return f, io.ErrUnexpectedEOF
		}
		sidLen := int(binary.BigEndian.Uint16(payload[0:2]))
		if len(payload) < 2+sidLen+8+2+2 {
			return f, io.ErrUnexpectedEOF
		}
		p := 2
		f.SessionID = string(payload[p : p+sidLen])
		p += sidLen
		f.LastOffset = binary.BigEndian.Uint64(payload[p:])
		p += 8
		f.Cols = binary.BigEndian.Uint16(payload[p:])
		p += 2
		f.Rows = binary.BigEndian.Uint16(payload[p:])
	case TypeHelloOK:
		if len(payload) < 16 {
			return f, io.ErrUnexpectedEOF
		}
		f.HeadOffset = binary.BigEndian.Uint64(payload[0:8])
		f.TailOffset = binary.BigEndian.Uint64(payload[8:16])
	case TypeData:
		if len(payload) < 8 {
			return f, io.ErrUnexpectedEOF
		}
		f.Offset = binary.BigEndian.Uint64(payload[0:8])
		f.Bytes = append([]byte(nil), payload[8:]...)
	case TypeInput:
		f.Bytes = append([]byte(nil), payload...)
	case TypeResize:
		if len(payload) < 4 {
			return f, io.ErrUnexpectedEOF
		}
		f.Cols = binary.BigEndian.Uint16(payload[0:2])
		f.Rows = binary.BigEndian.Uint16(payload[2:4])
	case TypeReplayFrom:
		if len(payload) < 8 {
			return f, io.ErrUnexpectedEOF
		}
		f.OffsetArg = binary.BigEndian.Uint64(payload)
	case TypeAck:
		if len(payload) < 8 {
			return f, io.ErrUnexpectedEOF
		}
		f.OffsetArg = binary.BigEndian.Uint64(payload)
	case TypeBye:
		// no payload
	default:
		return f, fmt.Errorf("%w: %d", ErrUnknownType, t)
	}
	return f, nil
}

// Decoder reassembles frames from an arbitrarily chunked byte stream. It is not
// safe for concurrent use; drive it from a single reader goroutine.
type Decoder struct {
	buf []byte
}

// Feed appends chunk and returns every frame that became complete. Leftover
// bytes are retained for the next call.
func (d *Decoder) Feed(chunk []byte) ([]Frame, error) {
	if len(chunk) == 0 {
		return nil, nil
	}
	d.buf = append(d.buf, chunk...)
	var frames []Frame
	off := 0
	for len(d.buf)-off >= headerSize {
		length := int(binary.BigEndian.Uint32(d.buf[off+1 : off+5]))
		if length < 0 || length > MaxPayload {
			return frames, fmt.Errorf("%w: %d", ErrFrameTooLarge, length)
		}
		if len(d.buf)-off-headerSize < length {
			break // wait for more bytes
		}
		t := Type(d.buf[off])
		payload := d.buf[off+headerSize : off+headerSize+length]
		f, err := decodePayload(t, payload)
		if err != nil {
			return frames, err
		}
		frames = append(frames, f)
		off += headerSize + length
	}
	if off > 0 {
		d.buf = append([]byte(nil), d.buf[off:]...)
	}
	return frames, nil
}
