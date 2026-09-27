package main

import (
	"errors"
	"fmt"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

// Win32-OpenSSH runs each session inside a Job Object with KILL_ON_JOB_CLOSE:
// when the session ends, everything still in the job dies, so a daemon
// launched the Unix way would go with it. Its jobs also carry BREAKAWAY_OK,
// which lets a child leave the job if it asks at creation time; that is how
// the daemon outlives the session, with no administrator rights (ADR-0009 §5,
// measured in the Win32-OpenSSH experiment). The daemon then creates its
// ConPTY shells itself, outside any job.

// IsProcessInJob is not exported by x/sys/windows.
var procIsProcessInJob = windows.NewLazySystemDLL("kernel32.dll").NewProc("IsProcessInJob")

// detachFlags start the daemon with no console and in its own process group,
// so the session's console and Ctrl+C never reach it.
const detachFlags = windows.DETACHED_PROCESS | windows.CREATE_NEW_PROCESS_GROUP

// detachAttr checks the job the front runs in and returns how to launch a
// daemon that survives it, or E_JOB_NO_BREAKAWAY when the job would kill it
// and forbids leaving. Nothing that needs an administrator is tried instead.
func detachAttr() (*syscall.SysProcAttr, error) {
	inJob, limits, err := currentJob()
	if err != nil {
		return nil, err
	}
	flags, err := launchFlags(inJob, limits)
	if err != nil {
		return nil, err
	}
	return &syscall.SysProcAttr{CreationFlags: flags, HideWindow: true}, nil
}

// launchFlags decides the daemon's creation flags from the front's job: its
// membership and its limit flags (JOBOBJECT_BASIC_LIMIT_INFORMATION.LimitFlags).
func launchFlags(inJob bool, limits uint32) (uint32, error) {
	switch {
	case !inJob:
		return detachFlags, nil
	case limits&windows.JOB_OBJECT_LIMIT_BREAKAWAY_OK != 0:
		// Leave the job even if it does not kill on close: whoever owns it
		// could still terminate it.
		return detachFlags | windows.CREATE_BREAKAWAY_FROM_JOB, nil
	case limits&windows.JOB_OBJECT_LIMIT_SILENT_BREAKAWAY_OK != 0:
		return detachFlags, nil // children leave the job on their own
	case limits&windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE != 0:
		return 0, withCode(codeJobNoBreakaway, fmt.Errorf(
			"the SSH session's job (limits %#x) kills its processes when the session ends and does not allow leaving it", limits))
	default:
		return detachFlags, nil
	}
}

// currentJob reports whether this process runs in a job and, if so, the limit
// flags of its immediate job.
func currentJob() (bool, uint32, error) {
	var in int32 // BOOL
	if r, _, err := procIsProcessInJob.Call(uintptr(windows.CurrentProcess()), 0, uintptr(unsafe.Pointer(&in))); r == 0 {
		return false, 0, fmt.Errorf("IsProcessInJob: %w", err)
	}
	if in == 0 {
		return false, 0, nil
	}
	var info windows.JOBOBJECT_EXTENDED_LIMIT_INFORMATION
	// Job handle 0: the job this process belongs to.
	if err := windows.QueryInformationJobObject(0, windows.JobObjectExtendedLimitInformation,
		uintptr(unsafe.Pointer(&info)), uint32(unsafe.Sizeof(info)), nil); err != nil {
		return true, 0, fmt.Errorf("QueryInformationJobObject: %w", err)
	}
	return true, info.BasicLimitInformation.LimitFlags, nil
}

// spawnFailure explains a failed launch. With nested jobs, the immediate job
// may allow breakaway while an outer one does not; CreateProcess then refuses
// with access denied, which means the same as E_JOB_NO_BREAKAWAY.
func spawnFailure(attr *syscall.SysProcAttr, err error) error {
	if attr.CreationFlags&windows.CREATE_BREAKAWAY_FROM_JOB != 0 && errors.Is(err, windows.ERROR_ACCESS_DENIED) {
		return withCode(codeJobNoBreakaway, fmt.Errorf("leaving the SSH session's job was refused: %w", err))
	}
	return err
}
