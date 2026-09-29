//go:build darwin || freebsd

package procmem

import "testing"

func TestParsePs(t *testing.T) {
	procs := parsePs("    1     0  1024\n  200     1   512\nbad line\n")
	if len(procs) != 2 || procs[1].pid != 200 || procs[1].ppid != 1 || procs[1].rss != 512*1024 {
		t.Fatalf("parsePs = %+v", procs)
	}
}
