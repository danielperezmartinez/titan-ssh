package procmem

import (
	"errors"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

// processMemoryCounters is PROCESS_MEMORY_COUNTERS (psapi.h).
type processMemoryCounters struct {
	cb                         uint32
	pageFaultCount             uint32
	peakWorkingSetSize         uintptr
	workingSetSize             uintptr
	quotaPeakPagedPoolUsage    uintptr
	quotaPagedPoolUsage        uintptr
	quotaPeakNonPagedPoolUsage uintptr
	quotaNonPagedPoolUsage     uintptr
	pagefileUsage              uintptr
	peakPagefileUsage          uintptr
}

// K32GetProcessMemoryInfo is the kernel32 export of psapi's
// GetProcessMemoryInfo (Windows 7 and later).
var procGetProcessMemoryInfo = windows.NewLazySystemDLL("kernel32.dll").NewProc("K32GetProcessMemoryInfo")

// list walks a Toolhelp snapshot for names and parents, and reads each
// process's working set, CPU and creation times. Processes the agent may not
// open (other users') are skipped: they can never be part of its sessions.
func list() ([]proc, error) {
	snap, err := windows.CreateToolhelp32Snapshot(windows.TH32CS_SNAPPROCESS, 0)
	if err != nil {
		return nil, err
	}
	defer windows.CloseHandle(snap)
	var e windows.ProcessEntry32
	e.Size = uint32(unsafe.Sizeof(e))
	var procs []proc
	for err = windows.Process32First(snap, &e); err == nil; err = windows.Process32Next(snap, &e) {
		if p, ok := readProc(e.ProcessID, e.ParentProcessID); ok {
			p.name = windows.UTF16ToString(e.ExeFile[:])
			procs = append(procs, p)
		}
	}
	if !errors.Is(err, windows.ERROR_NO_MORE_FILES) {
		return nil, err
	}
	return procs, nil
}

func readProc(pid, ppid uint32) (proc, bool) {
	h, err := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, pid)
	if err != nil {
		return proc{}, false
	}
	defer windows.CloseHandle(h)
	var created, exited, kernel, user windows.Filetime
	if err := windows.GetProcessTimes(h, &created, &exited, &kernel, &user); err != nil {
		return proc{}, false
	}
	var mc processMemoryCounters
	mc.cb = uint32(unsafe.Sizeof(mc))
	if r, _, _ := procGetProcessMemoryInfo.Call(uintptr(h), uintptr(unsafe.Pointer(&mc)), uintptr(mc.cb)); r == 0 {
		return proc{}, false
	}
	return proc{
		pid:   int(pid),
		ppid:  int(ppid),
		rss:   uint64(mc.workingSetSize),
		cpu:   span(kernel) + span(user),
		start: uint64(created.Nanoseconds()),
	}, true
}

// span reads a FILETIME that holds a duration (100 ns units), not a date.
func span(ft windows.Filetime) time.Duration {
	return time.Duration(uint64(ft.HighDateTime)<<32|uint64(ft.LowDateTime)) * 100
}

// Cwd is not available on Windows: another process's working directory is
// only readable from its memory, which the agent does not do.
func Cwd(int) string { return "" }
