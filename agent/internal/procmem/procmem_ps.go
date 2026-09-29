//go:build darwin || freebsd

package procmem

import (
	"os/exec"
	"strconv"
	"strings"
)

// list asks ps, which macOS and FreeBSD ship in the base system, for every
// process's parent and resident size in KiB. ps gives no sortable start time,
// so the recycled-PID check is skipped (start stays 0).
func list() ([]proc, error) {
	out, err := exec.Command("ps", "-A", "-o", "pid=", "-o", "ppid=", "-o", "rss=").Output()
	if err != nil {
		return nil, err
	}
	return parsePs(string(out)), nil
}

func parsePs(out string) []proc {
	var procs []proc
	for _, line := range strings.Split(out, "\n") {
		f := strings.Fields(line)
		if len(f) != 3 {
			continue
		}
		pid, err1 := strconv.Atoi(f[0])
		ppid, err2 := strconv.Atoi(f[1])
		kib, err3 := strconv.ParseUint(f[2], 10, 64)
		if err1 != nil || err2 != nil || err3 != nil {
			continue
		}
		procs = append(procs, proc{pid: pid, ppid: ppid, rss: kib * 1024})
	}
	return procs
}
