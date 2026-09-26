package session

import (
	"errors"
	"os"
	"sync"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

// NewPty starts the destination's default shell on a fresh ConPTY of the given
// size and returns it behind the Pty seam. It is the PtyFactory used by the
// daemon on Windows destinations.
func NewPty(cols, rows uint16) (Pty, error) {
	return startConPty(syscall.EscapeArg(defaultShell()), cols, rows)
}

// defaultShell is the shell Win32-OpenSSH is configured to use
// (HKLM\SOFTWARE\OpenSSH\DefaultShell), falling back to %COMSPEC%.
func defaultShell() string {
	if k, err := registry.OpenKey(registry.LOCAL_MACHINE, `SOFTWARE\OpenSSH`, registry.QUERY_VALUE); err == nil {
		shell, _, err := k.GetStringValue("DefaultShell")
		_ = k.Close()
		if err == nil && shell != "" {
			return shell
		}
	}
	if comspec := os.Getenv("COMSPEC"); comspec != "" {
		return comspec
	}
	return "cmd.exe"
}

// startConPty runs cmdline attached to a new pseudo console. os/exec cannot
// pass the pseudo-console attribute, so the process is created directly.
func startConPty(cmdline string, cols, rows uint16) (Pty, error) {
	if windows.NewLazySystemDLL("kernel32.dll").NewProc("CreatePseudoConsole").Find() != nil {
		return nil, ptyError(CodeNoConPty, errors.New("ConPTY needs Windows 10 1809 / Server 2019 or later"))
	}
	var inR, inW, outR, outW windows.Handle
	if err := windows.CreatePipe(&inR, &inW, nil, 0); err != nil {
		return nil, ptyError(CodePty, err)
	}
	if err := windows.CreatePipe(&outR, &outW, nil, 0); err != nil {
		closeHandles(inR, inW)
		return nil, ptyError(CodePty, err)
	}
	var hpc windows.Handle
	if err := windows.CreatePseudoConsole(coord(cols, rows), inR, outW, 0, &hpc); err != nil {
		closeHandles(inR, inW, outR, outW)
		return nil, ptyError(CodePty, err)
	}
	// The console holds its own references to its ends of the pipes.
	closeHandles(inR, outW)

	process, err := createAttached(cmdline, hpc)
	if err != nil {
		// Pipes first: with nobody reading the output, ClosePseudoConsole
		// could otherwise block on the console's last write.
		closeHandles(inW, outR)
		windows.ClosePseudoConsole(hpc)
		return nil, ptyError(CodePty, err)
	}
	p := &conPty{
		hpc:     hpc,
		process: process,
		in:      os.NewFile(uintptr(inW), "conpty-in"),
		out:     os.NewFile(uintptr(outR), "conpty-out"),
		exited:  make(chan struct{}),
	}
	go p.waitExit()
	return p, nil
}

// createAttached creates the process with the pseudo console as its console
// and returns its handle.
func createAttached(cmdline string, hpc windows.Handle) (windows.Handle, error) {
	attrs, err := windows.NewProcThreadAttributeList(1)
	if err != nil {
		return 0, err
	}
	defer attrs.Delete()
	// The attribute takes the HPCON value itself, not a pointer to it; go vet
	// reports a possible misuse of unsafe.Pointer here, which is expected.
	if err := attrs.Update(windows.PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE, unsafe.Pointer(hpc), unsafe.Sizeof(hpc)); err != nil {
		return 0, err
	}
	var si windows.StartupInfoEx
	si.Cb = uint32(unsafe.Sizeof(si))
	si.ProcThreadAttributeList = attrs.List()
	// Null std handles: without this flag the child inherits the agent's own
	// (redirected) stdio instead of the pseudo console.
	si.Flags = windows.STARTF_USESTDHANDLES
	cmd, err := windows.UTF16PtrFromString(cmdline)
	if err != nil {
		return 0, err
	}
	var pi windows.ProcessInformation
	flags := uint32(windows.EXTENDED_STARTUPINFO_PRESENT | windows.CREATE_UNICODE_ENVIRONMENT)
	if err := windows.CreateProcess(nil, cmd, nil, nil, false, flags, nil, nil, &si.StartupInfo, &pi); err != nil {
		return 0, err
	}
	_ = windows.CloseHandle(pi.Thread)
	return pi.Process, nil
}

type conPty struct {
	hpc     windows.Handle
	in, out *os.File

	closeConsole sync.Once

	mu         sync.Mutex // guards process against use after waitExit closes it
	process    windows.Handle
	procClosed bool
	exited     chan struct{} // closed once the process has exited and been released
}

func (p *conPty) Read(b []byte) (int, error)  { return p.out.Read(b) }
func (p *conPty) Write(b []byte) (int, error) { return p.in.Write(b) }

func (p *conPty) Resize(cols, rows uint16) error {
	return windows.ResizePseudoConsole(p.hpc, coord(cols, rows))
}

// waitExit closes the pseudo console when the shell exits: ConPTY keeps the
// output pipe open until then, and Session.pump waits for EOF on it.
func (p *conPty) waitExit() {
	_, _ = windows.WaitForSingleObject(p.process, windows.INFINITE)
	p.release()
	p.mu.Lock()
	_ = windows.CloseHandle(p.process)
	p.procClosed = true
	p.mu.Unlock()
	close(p.exited)
}

// release closes the pseudo console once. It may wait for the console's last
// output to be read, which Session.pump always does.
func (p *conPty) release() {
	p.closeConsole.Do(func() { windows.ClosePseudoConsole(p.hpc) })
}

func (p *conPty) Close() error {
	p.release()
	p.mu.Lock()
	if !p.procClosed {
		_ = windows.TerminateProcess(p.process, 1)
	}
	p.mu.Unlock()
	<-p.exited
	return errors.Join(p.in.Close(), p.out.Close())
}

func coord(cols, rows uint16) windows.Coord {
	return windows.Coord{X: int16(cols), Y: int16(rows)}
}

func closeHandles(hs ...windows.Handle) {
	for _, h := range hs {
		_ = windows.CloseHandle(h)
	}
}
