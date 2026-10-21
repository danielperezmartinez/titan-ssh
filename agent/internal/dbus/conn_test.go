//go:build unix

package dbus

import "testing"

func TestUnixPath(t *testing.T) {
	for in, want := range map[string]string{
		"unix:path=/run/user/1000/bus":               "/run/user/1000/bus",
		"unix:abstract=/tmp/dbus-x,guid=12":          "@/tmp/dbus-x",
		"unix:path=/tmp/a%20b,guid=1":                "/tmp/a b",
		"tcp:host=x;unix:path=/run/user/1000/bus,g=": "",
	} {
		got, ok := unixPath(in)
		if want == "" {
			if ok {
				t.Errorf("%q: %q accepted", in, got)
			}
			continue
		}
		if !ok || got != want {
			t.Errorf("%q = %q, %v; want %q", in, got, ok, want)
		}
	}
}
