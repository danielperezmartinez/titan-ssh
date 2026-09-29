package procmem

import (
	"os"
	"strconv"
	"strings"
)

// list reads /proc: the parent and start time from /proc/<pid>/stat, the
// resident pages from /proc/<pid>/statm. Processes that exit mid-scan are
// skipped.
func list() ([]proc, error) {
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return nil, err
	}
	page := uint64(os.Getpagesize())
	var procs []proc
	for _, e := range entries {
		pid, err := strconv.Atoi(e.Name())
		if err != nil {
			continue
		}
		p, ok := readProc(pid, page)
		if ok {
			procs = append(procs, p)
		}
	}
	return procs, nil
}

func readProc(pid int, page uint64) (proc, bool) {
	dir := "/proc/" + strconv.Itoa(pid)
	stat, err := os.ReadFile(dir + "/stat")
	if err != nil {
		return proc{}, false
	}
	ppid, start, ok := parseStat(string(stat))
	if !ok {
		return proc{}, false
	}
	statm, err := os.ReadFile(dir + "/statm")
	if err != nil {
		return proc{}, false
	}
	fields := strings.Fields(string(statm))
	if len(fields) < 2 {
		return proc{}, false
	}
	resident, err := strconv.ParseUint(fields[1], 10, 64)
	if err != nil {
		return proc{}, false
	}
	return proc{pid: pid, ppid: ppid, rss: resident * page, start: start}, true
}

// parseStat extracts the parent PID (field 4) and start time (field 22) of a
// /proc/<pid>/stat line. The command name (field 2) is parenthesised and may
// hold spaces or parentheses, so fields are counted after its last ')'.
func parseStat(s string) (ppid int, start uint64, ok bool) {
	i := strings.LastIndexByte(s, ')')
	if i < 0 {
		return 0, 0, false
	}
	fields := strings.Fields(s[i+1:])
	// fields[0] is field 3 (state), so field n is fields[n-3].
	if len(fields) < 20 {
		return 0, 0, false
	}
	ppid, err := strconv.Atoi(fields[1])
	if err != nil {
		return 0, 0, false
	}
	start, err = strconv.ParseUint(fields[19], 10, 64)
	if err != nil {
		return 0, 0, false
	}
	return ppid, start, true
}
