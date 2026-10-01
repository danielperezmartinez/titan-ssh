package protocol

// Input frames (ADR-0016): what a mouse pad session sends to `titan-agent
// --input`. They share the frame layout of the level-3 protocol but carry no
// offsets and are never replayed: an event lost to a network drop is gone.
//
// Payloads, big-endian:
//
//	POINTER_MOVE    dx i16, dy i16            relative motion in pixels, after the client's acceleration
//	POINTER_BUTTON  button u8, pressed u8     Button*; 1 = down, 0 = up
//	SCROLL          dx i16, dy i16            in 1/120 of a wheel notch; dy > 0 scrolls up, dx > 0 right
//	TEXT            UTF-8 bytes               typed as characters, whatever the destination's layout
//	KEY             key u16, mods u8, act u8  a Key* code, Mod* bits and an Action*
//	INPUT_READY     [flags u8]                agent -> client: the injector is ready; bit 0 = blocked
//
// The agent sends INPUT_READY when the connection opens and again whenever
// the blocked state changes (the desktop was locked or unlocked).
//
// Keys are this protocol's own catalogue, not Windows virtual keys or X11
// keysyms, so each injector translates them.

// Pointer buttons.
const (
	ButtonLeft   uint8 = 1
	ButtonRight  uint8 = 2
	ButtonMiddle uint8 = 3
)

// Modifier bits of a KEY frame.
const (
	ModShift uint8 = 1 << 0
	ModCtrl  uint8 = 1 << 1
	ModAlt   uint8 = 1 << 2
	ModMeta  uint8 = 1 << 3 // Windows key / Super
)

// Key actions. ActionPress taps the key with the frame's modifiers held around
// it; ActionDown and ActionUp move the key alone (a held modifier, say) and
// ignore the modifier bits.
const (
	ActionPress uint8 = 0
	ActionDown  uint8 = 1
	ActionUp    uint8 = 2
)

// Key codes. Letters and digits exist as keys so that shortcuts such as
// Ctrl+C can be sent; plain typing goes as TEXT.
const (
	KeyEnter          uint16 = 1
	KeyEscape         uint16 = 2
	KeyBackspace      uint16 = 3
	KeyTab            uint16 = 4
	KeySpace          uint16 = 5
	KeyDelete         uint16 = 6
	KeyInsert         uint16 = 7
	KeyHome           uint16 = 8
	KeyEnd            uint16 = 9
	KeyPageUp         uint16 = 10
	KeyPageDown       uint16 = 11
	KeyArrowLeft      uint16 = 12
	KeyArrowRight     uint16 = 13
	KeyArrowUp        uint16 = 14
	KeyArrowDown      uint16 = 15
	KeyF1             uint16 = 16 // F1..F12 are 16..27
	KeyF12            uint16 = 27
	KeyPrintScreen    uint16 = 28
	KeyContextMenu    uint16 = 29
	KeyVolumeUp       uint16 = 30
	KeyVolumeDown     uint16 = 31
	KeyVolumeMute     uint16 = 32
	KeyMediaPlayPause uint16 = 33
	KeyMediaNext      uint16 = 34
	KeyMediaPrevious  uint16 = 35

	KeyA uint16 = 100 // A..Z are 100..125
	KeyZ uint16 = 125
	Key0 uint16 = 130 // 0..9 are 130..139
	Key9 uint16 = 139

	KeyShift uint16 = 200
	KeyCtrl  uint16 = 201
	KeyAlt   uint16 = 202
	KeyMeta  uint16 = 203
)
