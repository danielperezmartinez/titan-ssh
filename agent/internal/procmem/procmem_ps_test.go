//go:build darwin || freebsd

package procmem

import (
	"testing"
	"time"
)

func TestParsePs(t *testing.T) {
	procs := parsePs("    1     0  1024   0:01.50 /sbin/launchd\n  200     1   512   1:02:03 /Applications/My App/bin/my app\nbad line\n")
	if len(procs) != 2 || procs[1].pid != 200 || procs[1].ppid != 1 || procs[1].rss != 512*1024 {
		t.Fatalf("parsePs = %+v", procs)
	}
	if procs[0].name != "launchd" || procs[0].cpu != 1500*time.Millisecond {
		t.Fatalf("first = %+v", procs[0])
	}
	if procs[1].name != "my app" || procs[1].cpu != time.Hour+2*time.Minute+3*time.Second {
		t.Fatalf("second = %+v", procs[1])
	}
}

func TestParsePsTime(t *testing.T) {
	for in, want := range map[string]time.Duration{
		"0:00.03":    30 * time.Millisecond,
		"12:34":      12*time.Minute + 34*time.Second,
		"2-01:00:00": 49 * time.Hour,
	} {
		if got, ok := parsePsTime(in); !ok || got != want {
			t.Errorf("parsePsTime(%q) = %v, %v; want %v", in, got, ok, want)
		}
	}
	if _, ok := parsePsTime("x"); ok {
		t.Error("garbage must be rejected")
	}
}
