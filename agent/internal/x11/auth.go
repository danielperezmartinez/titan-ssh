package x11

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"strings"
	"time"
)

// Xauthority address families.
const (
	familyLocal = 256
	familyWild  = 65535
)

// AuthEntry is one entry of an Xauthority file.
type AuthEntry struct {
	Family  uint16
	Address string
	Number  string // the display number, "" for any
	Name    string // e.g. MIT-MAGIC-COOKIE-1
	Data    []byte
}

// ParseXauthority reads the entries of an Xauthority file (big-endian,
// length-prefixed fields).
func ParseXauthority(r io.Reader) ([]AuthEntry, error) {
	var out []AuthEntry
	readField := func() ([]byte, error) {
		var n uint16
		if err := binary.Read(r, binary.BigEndian, &n); err != nil {
			return nil, err
		}
		b := make([]byte, n)
		_, err := io.ReadFull(r, b)
		return b, err
	}
	for {
		var fam uint16
		if err := binary.Read(r, binary.BigEndian, &fam); err != nil {
			if errors.Is(err, io.EOF) {
				return out, nil
			}
			return out, err
		}
		var fields [4][]byte
		for i := range fields {
			f, err := readField()
			if err != nil {
				return out, fmt.Errorf("x11: malformed Xauthority: %w", err)
			}
			fields[i] = f
		}
		out = append(out, AuthEntry{Family: fam, Address: string(fields[0]), Number: string(fields[1]),
			Name: string(fields[2]), Data: fields[3]})
	}
}

// pickCookie chooses the MIT-MAGIC-COOKIE-1 for local display number among
// entries: one for this host first, then any local or wildcard one.
func pickCookie(entries []AuthEntry, number, hostname string) ([]byte, bool) {
	var fallback []byte
	for _, e := range entries {
		if e.Name != "MIT-MAGIC-COOKIE-1" || (e.Number != number && e.Number != "") {
			continue
		}
		if e.Family == familyLocal && e.Address == hostname {
			return e.Data, true
		}
		if (e.Family == familyLocal || e.Family == familyWild) && fallback == nil {
			fallback = e.Data
		}
	}
	return fallback, fallback != nil
}

// Display is a parsed DISPLAY value for a local server: ":1", ":1.0" or
// "unix:1". Remote displays (host:N, as SSH X11 forwarding sets) are not the
// user's desktop and are refused.
type Display struct {
	Number string
}

// ParseDisplay parses a local DISPLAY value.
func ParseDisplay(s string) (Display, error) {
	host, rest, ok := strings.Cut(s, ":")
	if !ok || (host != "" && host != "unix") {
		return Display{}, fmt.Errorf("x11: %q is not a local display", s)
	}
	num, _, _ := strings.Cut(rest, ".")
	if _, err := strconv.Atoi(num); err != nil || num == "" {
		return Display{}, fmt.Errorf("x11: %q is not a display", s)
	}
	return Display{Number: num}, nil
}

// Dial connects to the local display d with the cookie from the Xauthority
// file at xauthority (none if it is "" or has no entry for d).
func Dial(d Display, xauthority string) (*Conn, error) {
	path := "/tmp/.X11-unix/X" + d.Number
	var c net.Conn
	var err error
	// Linux servers also listen on the abstract socket of the same name,
	// reachable when /tmp is not shared (say, a private /tmp).
	for _, addr := range []string{path, "@" + path} {
		c, err = net.DialTimeout("unix", addr, 5*time.Second)
		if err == nil {
			break
		}
	}
	if err != nil {
		return nil, fmt.Errorf("x11: cannot reach display :%s: %w", d.Number, err)
	}
	var name string
	var data []byte
	if xauthority != "" {
		if f, ferr := os.Open(xauthority); ferr == nil {
			entries, _ := ParseXauthority(f)
			f.Close()
			host, _ := os.Hostname()
			if cookie, ok := pickCookie(entries, d.Number, host); ok {
				name, data = "MIT-MAGIC-COOKIE-1", cookie
			}
		}
	}
	return NewConn(c, name, data)
}
