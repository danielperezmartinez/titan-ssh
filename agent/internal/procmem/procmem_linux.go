package procmem

import (
	"os"
	"strconv"
	"strings"
	"time"
)

// clockTick is USER_HZ, the unit of the CPU times in /proc/<pid>/stat. It is
// 100 on every Linux architecture the agent ships for (the kernel exports
// that value to user space whatever its internal HZ is).
const clockTick = 10 * time.Millisecond

// list reads /proc: the name, parent, CPU time and start time from
// /proc/<pid>/stat, the resident pages from /proc/<pid>/statm. Processes that
// exit mid-scan are skipped.
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
	p, ok := parseStat(string(stat))
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
	p.pid = pid
	p.rss = resident * page
	return p, true
}

// parseStat extracts the command name (field 2), the parent PID (field 4),
// the user and system CPU times (fields 14 and 15) and the start time (field
// 22) of a /proc/<pid>/stat line. The name is parenthesised and may hold
// spaces or parentheses, so fields are counted after its last ')'.
func parseStat(s string) (proc, bool) {
	open, close := strings.IndexByte(s, '('), strings.LastIndexByte(s, ')')
	if open < 0 || close < open {
		return proc{}, false
	}
	fields := strings.Fields(s[close+1:])
	// fields[0] is field 3 (state), so field n is fields[n-3].
	if len(fields) < 20 {
		return proc{}, false
	}
	ppid, err := strconv.Atoi(fields[1])
	if err != nil {
		return proc{}, false
	}
	utime, err1 := strconv.ParseUint(fields[11], 10, 64)
	stime, err2 := strconv.ParseUint(fields[12], 10, 64)
	start, err3 := strconv.ParseUint(fields[19], 10, 64)
	if err1 != nil || err2 != nil || err3 != nil {
		return proc{}, false
	}
	return proc{
		ppid:  ppid,
		name:  s[open+1 : close],
		cpu:   time.Duration(utime+stime) * clockTick,
		start: start,
	}, true
}

// Cwd is pid's working directory, or "" when it cannot be read.
func Cwd(pid int) string {
	dir, err := os.Readlink("/proc/" + strconv.Itoa(pid) + "/cwd")
	if err != nil {
		return ""
	}
	return dir
}
