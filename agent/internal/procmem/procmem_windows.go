package procmem

import (
	"errors"
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

// list walks a Toolhelp snapshot for parents, and reads each process's working
// set and creation time. Processes the agent may not open (other users') are
// skipped: they can never be part of its sessions.
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
		start: uint64(created.Nanoseconds()),
	}, true
}
