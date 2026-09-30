package session

// titleMax bounds a window title, so a program cannot make the agent hold an
// unbounded OSC string.
const titleMax = 256

// titleScanner follows a session's output for the window title the programs
// in it set with OSC 0 or OSC 2 (ESC ] 0 ; text BEL, or ended by ESC \). It
// keeps its state between chunks, since a sequence may be split across two
// reads.
type titleScanner struct {
	state   int
	payload []byte
	tooLong bool // the OSC outgrew titleMax: it is skipped to its end
	title   string
}

const (
	scanText   = iota
	scanEsc    // saw ESC
	scanOsc    // inside ESC ]
	scanOscEsc // saw ESC inside an OSC: ESC \ ends it
)

// Feed scans b and reports whether the title changed.
func (s *titleScanner) Feed(b []byte) bool {
	changed := false
	for _, c := range b {
		switch s.state {
		case scanText:
			if c == 0x1b {
				s.state = scanEsc
			}
		case scanEsc:
			s.afterEsc(c)
		case scanOsc:
			switch c {
			case 0x07:
				changed = s.end() || changed
			case 0x1b:
				s.state = scanOscEsc
			default:
				if len(s.payload) >= titleMax+2 {
					s.tooLong = true
				} else {
					s.payload = append(s.payload, c)
				}
			}
		case scanOscEsc:
			if c == '\\' {
				changed = s.end() || changed
			} else {
				// Another escape interrupts the OSC.
				s.afterEsc(c)
			}
		}
	}
	return changed
}

// afterEsc handles the byte after an ESC outside an OSC.
func (s *titleScanner) afterEsc(c byte) {
	switch c {
	case ']':
		s.state, s.payload, s.tooLong = scanOsc, s.payload[:0], false
	case 0x1b:
		s.state = scanEsc
	default:
		s.state = scanText
	}
}

// end closes an OSC sequence and keeps it if it sets the title.
func (s *titleScanner) end() bool {
	s.state = scanText
	p := s.payload
	if s.tooLong || len(p) < 2 || (p[0] != '0' && p[0] != '2') || p[1] != ';' {
		return false
	}
	title := string(p[2:])
	if title == s.title {
		return false
	}
	s.title = title
	return true
}
