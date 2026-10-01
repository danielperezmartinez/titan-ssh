package main

import (
	"bytes"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"

	"golang.org/x/sys/windows"
)

// desktopSupported says whether this system has a desktop helper, and so a
// "desktop" part in --status.
const desktopSupported = true

// desktopSession is the Windows session this process runs in.
func desktopSession() int {
	var s uint32
	if err := windows.ProcessIdToSessionId(windows.GetCurrentProcessId(), &s); err != nil {
		return 0
	}
	return int(s)
}

// desktopTaskName is the user's scheduled task for the helper. Task names are
// shared by every user of the PC, so it carries the user name.
func desktopTaskName() string {
	user := strings.Map(func(r rune) rune {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9', r == '-', r == '_', r == '.':
			return r
		}
		return '_'
	}, os.Getenv("USERNAME"))
	return "titan-ssh-desktop-" + user
}

// launchDesktop starts the helper on the user's desktop (ADR-0016 §3,
// ADR-0017): it refreshes the GUI copy of this binary, (re)registers the
// scheduled task that runs it only while the user is signed in, and runs the
// task. Windows starts it in the user's own session, which a process under
// sshd cannot do itself. Re-registering every time keeps the task pointing at
// the current version.
func launchDesktop(stateDir string) error {
	exe, err := ensureDesktopCopy(stateDir)
	if err != nil {
		return withCode(codeDesktopTask, err)
	}
	f, err := os.CreateTemp(stateDir, ".desktop-task-*.xml")
	if err != nil {
		return withCode(codeDesktopTask, err)
	}
	defer os.Remove(f.Name())
	_, werr := f.Write(desktopTaskXML(exe, []string{"--desktop", "--state-dir", stateDir}))
	if cerr := f.Close(); werr == nil {
		werr = cerr
	}
	if werr != nil {
		return withCode(codeDesktopTask, werr)
	}
	name := desktopTaskName()
	if out, err := schtasks("/create", "/tn", name, "/xml", f.Name(), "/f"); err != nil {
		return withCode(codeDesktopTask, fmt.Errorf("creating the scheduled task %s: %v: %s", name, err, out))
	}
	if out, err := schtasks("/run", "/tn", name); err != nil {
		return withCode(codeDesktopTask, fmt.Errorf("running the scheduled task %s: %v: %s", name, err, out))
	}
	return nil
}

// ensureDesktopCopy writes desktop-<version>.exe, this executable with the
// GUI subsystem, unless it is already there as it should be, and deletes the
// copies of other versions.
func ensureDesktopCopy(stateDir string) (string, error) {
	self, err := os.Executable()
	if err != nil {
		return "", err
	}
	data, err := os.ReadFile(self)
	if err != nil {
		return "", err
	}
	gui, err := withGUISubsystem(data)
	if err != nil {
		return "", err
	}
	path := filepath.Join(stateDir, desktopCopyName())
	if cur, err := os.ReadFile(path); err != nil || !bytes.Equal(cur, gui) {
		tmp, err := os.CreateTemp(stateDir, ".desktop-*.exe")
		if err != nil {
			return "", err
		}
		defer os.Remove(tmp.Name()) // no-op once renamed
		_, werr := tmp.Write(gui)
		if cerr := tmp.Close(); werr == nil {
			werr = cerr
		}
		if werr != nil {
			return "", werr
		}
		if err := renameRetrying(tmp.Name(), path); err != nil {
			return "", err
		}
	}
	removeDesktopCopies(stateDir, desktopCopyName())
	return path, nil
}

func desktopTaskExists() bool {
	_, err := schtasks("/query", "/tn", desktopTaskName())
	return err == nil
}

func deleteDesktopTask() error {
	if !desktopTaskExists() {
		return nil
	}
	name := desktopTaskName()
	if out, err := schtasks("/delete", "/tn", name, "/f"); err != nil {
		return fmt.Errorf("deleting the scheduled task %s: %v: %s", name, err, out)
	}
	return nil
}

// schtasks runs the system's schtasks.exe, by full path so a schtasks
// elsewhere on PATH is never picked up. It returns its output on one line,
// with what is not ASCII replaced (it prints in the console code page).
func schtasks(args ...string) (string, error) {
	root := os.Getenv("SystemRoot")
	if root == "" {
		root = `C:\Windows`
	}
	cmd := exec.Command(filepath.Join(root, "System32", "schtasks.exe"), args...)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	out, err := cmd.CombinedOutput()
	text := strings.Map(func(r rune) rune {
		if r < 0x20 || r > 0x7e {
			return ' '
		}
		return r
	}, string(out))
	return strings.Join(strings.Fields(text), " "), err
}
