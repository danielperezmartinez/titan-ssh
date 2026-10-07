package main

import (
	"errors"
	"fmt"
	"unsafe"

	"golang.org/x/sys/windows"
)

// maxCommandLine is the most UTF-16 units CreateProcess takes in a command
// line, its terminating NUL included.
const maxCommandLine = 32767

// startProgram starts req on this process's desktop (ADR-0019 §3) with this
// process's own token: the helper's, unelevated. It never goes through a
// shell or ShellExecute, so nothing can raise a UAC prompt: a program that
// needs an administrator fails with ERROR_ELEVATION_REQUIRED. A console
// program gets a console of its own, and every window is shown normally.
func startProgram(req runRequest) (int, error) {
	cmdline := windows.ComposeCommandLine(append([]string{req.Program}, req.Args...))
	line, err := windows.UTF16FromString(cmdline)
	if err != nil {
		return 0, err
	}
	if len(line) > maxCommandLine {
		return 0, fmt.Errorf("the command line is %d characters long, more than Windows takes", len(line)-1)
	}
	app, err := windows.UTF16PtrFromString(req.Program)
	if err != nil {
		return 0, err
	}
	var dir *uint16
	if req.Dir != "" {
		if dir, err = windows.UTF16PtrFromString(req.Dir); err != nil {
			return 0, err
		}
	}
	inJob, limits, err := currentJob()
	if err != nil {
		return 0, err
	}
	si := windows.StartupInfo{
		Cb:         uint32(unsafe.Sizeof(windows.StartupInfo{})),
		Flags:      windows.STARTF_USESHOWWINDOW,
		ShowWindow: windows.SW_SHOWNORMAL,
	}
	var pi windows.ProcessInformation
	err = windows.CreateProcess(app, &line[0], nil, nil, false,
		programFlags(inJob, limits), nil, dir, &si, &pi)
	if errors.Is(err, windows.ERROR_ELEVATION_REQUIRED) {
		return 0, fmt.Errorf("%s needs an administrator, and the desktop helper never elevates", req.Program)
	}
	if err != nil {
		return 0, fmt.Errorf("starting %s: %w", req.Program, err)
	}
	windows.CloseHandle(pi.Thread)
	windows.CloseHandle(pi.Process)
	return int(pi.ProcessId), nil
}

// programFlags are the creation flags of a program the helper starts. If the
// helper runs in a job that lets children leave, the program leaves it, so
// that whatever ends the helper's task does not end the program too.
func programFlags(inJob bool, limits uint32) uint32 {
	flags := uint32(windows.CREATE_NEW_CONSOLE | windows.CREATE_DEFAULT_ERROR_MODE)
	if inJob && limits&windows.JOB_OBJECT_LIMIT_BREAKAWAY_OK != 0 {
		flags |= windows.CREATE_BREAKAWAY_FROM_JOB
	}
	return flags
}
