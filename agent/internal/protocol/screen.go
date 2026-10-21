package protocol

import (
	"encoding/binary"
	"io"
)

// Screen frames (ADR-0029): what a remote desktop session exchanges with
// `titan-agent --screen`. The same connection also carries the input frames
// of input.go (client -> agent). Like input, nothing is replayed: after a
// drop, a new connection starts with SCREEN_SIZE and a whole frame.
//
// Payloads, big-endian:
//
//	SCREEN_HELLO      version u8, quality u8         client -> agent, first frame; quality 0 = lossless only,
//	                                                 1..100 = highest JPEG quality, QualityAuto = adapt to the link
//	SCREEN_SIZE       width u16, height u16          the screen's size; a whole frame follows
//	SCREEN_TILE       x u16, y u16, w u16, h u16,    one rectangle of the next frame
//	                  encoding u8, data
//	SCREEN_FRAME_END  seq u32                        the frame is complete: show it
//	SCREEN_ACK        seq u32                        client -> agent: frame seq was shown
//	CURSOR_SHAPE      w u16, h u16, hotX u16,        the cursor's image: w*h pixels, ARGB u32 BE with
//	                  hotY u16, data                 straight alpha, compressed with zlib
//	CURSOR_POS        x i16, y i16, visible u8       where the cursor's hot spot is
//	POINTER_TO        x u16, y u16                   client -> agent: put the pointer there
//
// Tile encodings (data of SCREEN_TILE):
//
//	ENCODING_FILL  r u8, g u8, b u8                 the whole rectangle in one color
//	ENCODING_ZLIB  zlib(RGB rows)                   w*h*3 bytes before compression, lossless
//	ENCODING_JPEG  a JPEG image of w×h              lossy
//
// A client that meets an encoding it does not know closes the connection; the
// version in SCREEN_HELLO lets a newer agent avoid encodings an older client
// lacks.

// ScreenVersion is the screen protocol version this side speaks.
const ScreenVersion = 1

// QualityAuto asks the agent to pick the JPEG quality from how fast frames are
// acknowledged.
const QualityAuto = 255

// MaxScreenPayload bounds a frame's payload on a screen connection: a tile is
// at most a few hundred kB, a cursor image at most 256×256 pixels.
const MaxScreenPayload = 4 * 1024 * 1024

// Tile encodings.
const (
	EncodingFill uint8 = 0
	EncodingZlib uint8 = 1
	EncodingJPEG uint8 = 2
)

func isScreen(t Type) bool { return t >= TypeScreenHello && t <= TypePointerTo }

func encodeScreen(f Frame) []byte {
	be := binary.BigEndian
	switch f.Type {
	case TypeScreenHello:
		return []byte{f.Version, f.Quality}
	case TypeScreenSize:
		b := make([]byte, 4)
		be.PutUint16(b[0:], uint16(f.Width))
		be.PutUint16(b[2:], uint16(f.Height))
		return b
	case TypeScreenTile:
		b := make([]byte, 9+len(f.Bytes))
		be.PutUint16(b[0:], uint16(f.X))
		be.PutUint16(b[2:], uint16(f.Y))
		be.PutUint16(b[4:], uint16(f.Width))
		be.PutUint16(b[6:], uint16(f.Height))
		b[8] = f.Encoding
		copy(b[9:], f.Bytes)
		return b
	case TypeScreenFrameEnd, TypeScreenAck:
		b := make([]byte, 4)
		be.PutUint32(b, f.Seq)
		return b
	case TypeCursorShape:
		b := make([]byte, 8+len(f.Bytes))
		be.PutUint16(b[0:], uint16(f.Width))
		be.PutUint16(b[2:], uint16(f.Height))
		be.PutUint16(b[4:], uint16(f.X))
		be.PutUint16(b[6:], uint16(f.Y))
		copy(b[8:], f.Bytes)
		return b
	case TypeCursorPos:
		b := make([]byte, 5)
		be.PutUint16(b[0:], uint16(int16(f.X)))
		be.PutUint16(b[2:], uint16(int16(f.Y)))
		if f.Visible {
			b[4] = 1
		}
		return b
	case TypePointerTo:
		b := make([]byte, 4)
		be.PutUint16(b[0:], uint16(f.X))
		be.PutUint16(b[2:], uint16(f.Y))
		return b
	}
	return nil
}

func decodeScreen(f Frame, p []byte) (Frame, error) {
	be := binary.BigEndian
	need := map[Type]int{
		TypeScreenHello: 2, TypeScreenSize: 4, TypeScreenTile: 9, TypeScreenFrameEnd: 4,
		TypeScreenAck: 4, TypeCursorShape: 8, TypeCursorPos: 5, TypePointerTo: 4,
	}[f.Type]
	if len(p) < need {
		return f, io.ErrUnexpectedEOF
	}
	switch f.Type {
	case TypeScreenHello:
		f.Version, f.Quality = p[0], p[1]
	case TypeScreenSize:
		f.Width, f.Height = int(be.Uint16(p[0:])), int(be.Uint16(p[2:]))
	case TypeScreenTile:
		f.X, f.Y = int(be.Uint16(p[0:])), int(be.Uint16(p[2:]))
		f.Width, f.Height = int(be.Uint16(p[4:])), int(be.Uint16(p[6:]))
		f.Encoding = p[8]
		f.Bytes = append([]byte(nil), p[9:]...)
	case TypeScreenFrameEnd, TypeScreenAck:
		f.Seq = be.Uint32(p)
	case TypeCursorShape:
		f.Width, f.Height = int(be.Uint16(p[0:])), int(be.Uint16(p[2:]))
		f.X, f.Y = int(be.Uint16(p[4:])), int(be.Uint16(p[6:]))
		f.Bytes = append([]byte(nil), p[8:]...)
	case TypeCursorPos:
		f.X, f.Y = int(int16(be.Uint16(p[0:]))), int(int16(be.Uint16(p[2:])))
		f.Visible = p[4] != 0
	case TypePointerTo:
		f.X, f.Y = int(be.Uint16(p[0:])), int(be.Uint16(p[2:]))
	}
	return f, nil
}
