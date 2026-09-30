package session

import (
	"strings"
	"testing"
)

func TestTitleScanner(t *testing.T) {
	cases := []struct {
		name   string
		chunks []string
		want   string
	}{
		{"osc 0 with BEL", []string{"x\x1b]0;user@host: ~/src\x07y"}, "user@host: ~/src"},
		{"osc 2 with ST", []string{"\x1b]2;vim notes.md\x1b\\"}, "vim notes.md"},
		{"split across chunks", []string{"\x1b]0;cl", "aude\x07"}, "claude"},
		{"last one wins", []string{"\x1b]0;a\x07\x1b]0;b\x07"}, "b"},
		{"other OSC ignored", []string{"\x1b]7;file://host/tmp\x07"}, ""},
		{"CSI is not OSC", []string{"\x1b[0;31mred\x07"}, ""},
		{"interrupted OSC", []string{"\x1b]0;half\x1b[1mtext\x07"}, ""},
		{"too long", []string{"\x1b]0;" + strings.Repeat("a", titleMax+10) + "\x07"}, ""},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			var s titleScanner
			for _, ch := range c.chunks {
				s.Feed([]byte(ch))
			}
			if s.title != c.want {
				t.Fatalf("title = %q; want %q", s.title, c.want)
			}
		})
	}
}

func TestTitleScannerReportsChanges(t *testing.T) {
	var s titleScanner
	if !s.Feed([]byte("\x1b]0;a\x07")) {
		t.Fatal("a new title is a change")
	}
	if s.Feed([]byte("\x1b]0;a\x07")) {
		t.Fatal("the same title again is not")
	}
}
