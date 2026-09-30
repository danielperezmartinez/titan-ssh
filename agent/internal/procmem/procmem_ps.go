//go:build darwin || freebsd

package procmem

import (
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// list asks ps, which macOS and FreeBSD ship in the base system, for every
// process's parent, resident size in KiB, CPU time and name. ps gives no
// sortable start time, so the recycled-PID check is skipped (start stays 0).
// The name goes last because it may hold spaces.
func list() ([]proc, error) {
	out, err := exec.Command("ps", "-A", "-o", "pid=", "-o", "ppid=", "-o", "rss=", "-o", "time=", "-o", "comm=").Output()
	if err != nil {
		return nil, err
	}
	return parsePs(string(out)), nil
}

func parsePs(out string) []proc {
	var procs []proc
	for _, line := range strings.Split(out, "\n") {
		f := strings.Fields(line)
		if len(f) < 5 {
			continue
		}
		pid, err1 := strconv.Atoi(f[0])
		ppid, err2 := strconv.Atoi(f[1])
		kib, err3 := strconv.ParseUint(f[2], 10, 64)
		cpu, ok := parsePsTime(f[3])
		if err1 != nil || err2 != nil || err3 != nil || !ok {
			continue
		}
		name := filepath.Base(strings.Join(f[4:], " "))
		procs = append(procs, proc{pid: pid, ppid: ppid, name: name, rss: kib * 1024, cpu: cpu})
	}
	return procs
}

// parsePsTime reads ps's cumulative CPU time: [[dd-]hh:]mm:ss[.cc].
func parsePsTime(s string) (time.Duration, bool) {
	var days int64
	if d, rest, ok := strings.Cut(s, "-"); ok {
		n, err := strconv.ParseInt(d, 10, 64)
		if err != nil {
			return 0, false
		}
		days, s = n, rest
	}
	parts := strings.Split(s, ":")
	if len(parts) < 2 || len(parts) > 3 {
		return 0, false
	}
	secs, err := strconv.ParseFloat(parts[len(parts)-1], 64)
	if err != nil {
		return 0, false
	}
	total := time.Duration(secs * float64(time.Second))
	unit := time.Minute
	for i := len(parts) - 2; i >= 0; i-- {
		n, err := strconv.ParseInt(parts[i], 10, 64)
		if err != nil {
			return 0, false
		}
		total += time.Duration(n) * unit
		unit = time.Hour
	}
	return total + time.Duration(days)*24*time.Hour, true
}

// Cwd is not read on macOS and FreeBSD: neither has /proc by default, and the
// tools that tell (lsof, procstat) are not in both base systems.
func Cwd(int) string { return "" }
