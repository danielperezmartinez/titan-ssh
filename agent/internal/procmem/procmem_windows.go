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

// Cwd is pid's working directory as the process keeps it, read from its
// memory (the CurrentDirectory of its PEB's process parameters), or "" when it
// cannot be read: another user's process, a 32-bit one under WOW64 (its
// directory lives in the 32-bit PEB) or one that has exited. Only cmd.exe
// moves it with `cd`; PowerShell leaves it where it started, which is why
// [Table.ShellDir] asks it of cmd.exe alone.
func Cwd(pid int) string {
	if pid <= 0 {
		return ""
	}
	h, err := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION|windows.PROCESS_VM_READ, false, uint32(pid))
	if err != nil {
		return ""
	}
	defer windows.CloseHandle(h)
	var machine, native uint16
	if err := windows.IsWow64Process2(h, &machine, &native); err != nil || machine != 0 {
		return ""
	}
	var info windows.PROCESS_BASIC_INFORMATION
	if err := windows.NtQueryInformationProcess(h, windows.ProcessBasicInformation, unsafe.Pointer(&info), uint32(unsafe.Sizeof(info)), nil); err != nil {
		return ""
	}
	var peb windows.PEB
	if !readRemote(h, uintptr(unsafe.Pointer(info.PebBaseAddress)), unsafe.Pointer(&peb), unsafe.Offsetof(peb.ProcessParameters)+unsafe.Sizeof(peb.ProcessParameters)) {
		return ""
	}
	var params windows.RTL_USER_PROCESS_PARAMETERS
	if !readRemote(h, uintptr(unsafe.Pointer(peb.ProcessParameters)), unsafe.Pointer(&params), unsafe.Offsetof(params.CurrentDirectory)+unsafe.Sizeof(params.CurrentDirectory)) {
		return ""
	}
	dos := params.CurrentDirectory.DosPath
	if dos.Length < 2 || dos.Buffer == nil {
		return ""
	}
	buf := make([]uint16, dos.Length/2)
	if !readRemote(h, uintptr(unsafe.Pointer(dos.Buffer)), unsafe.Pointer(&buf[0]), uintptr(len(buf)*2)) {
		return ""
	}
	return windows.UTF16ToString(buf)
}

// readRemote copies size bytes at addr in process h into dst, all or nothing.
func readRemote(h windows.Handle, addr uintptr, dst unsafe.Pointer, size uintptr) bool {
	if addr == 0 {
		return false
	}
	var done uintptr
	err := windows.ReadProcessMemory(h, addr, (*byte)(dst), size, &done)
	return err == nil && done == size
}

func shellDir(t *Table, shellPID int, reported string) string {
	return t.windowsShellDir(shellPID, reported, Cwd)
}
