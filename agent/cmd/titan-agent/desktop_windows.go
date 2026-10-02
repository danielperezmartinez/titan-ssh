package main

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
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

// The scheduled task's name is kept in the state dir, in desktopTaskFile.
// Task names are shared by every user of the PC, and any of them can create
// a task in the root folder, so a name others could predict could be taken
// first and keep the user's helper from ever registering. The name is the
// prefix, the user name for whoever reads the task list, and a random part;
// it is chosen on the first launch and kept until --remove-desktop.
const (
	desktopTaskFile   = "desktop-task.txt"
	desktopTaskPrefix = "titan-ssh-desktop-"
)

// recordedDesktopTask is the task name kept in stateDir, or "" if there is
// none (or what is there is not a name this agent would have chosen).
func recordedDesktopTask(stateDir string) string {
	data, err := os.ReadFile(filepath.Join(stateDir, desktopTaskFile))
	if err != nil {
		return ""
	}
	name := strings.TrimSpace(string(data))
	rest, ok := strings.CutPrefix(name, desktopTaskPrefix)
	if !ok || rest == "" || len(name) > 128 || strings.TrimFunc(rest, taskNameRune) != "" {
		return ""
	}
	return name
}

func taskNameRune(r rune) bool {
	return r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '-' || r == '_' || r == '.'
}

// desktopTaskName is stateDir's task name, chosen and recorded now if there is
// none yet. When two fronts race to record one, both end up with the first.
func desktopTaskName(stateDir string) (string, error) {
	if name := recordedDesktopTask(stateDir); name != "" {
		return name, nil
	}
	user := []rune(strings.Map(func(r rune) rune {
		if taskNameRune(r) {
			return r
		}
		return '_'
	}, os.Getenv("USERNAME")))
	if len(user) > 32 {
		user = user[:32]
	}
	random := make([]byte, 8)
	if _, err := rand.Read(random); err != nil {
		return "", err
	}
	name := desktopTaskPrefix + string(user) + "-" + hex.EncodeToString(random)
	f, err := createPrivateTemp(stateDir, ".desktop-task-*.txt")
	if err != nil {
		return "", err
	}
	defer os.Remove(f.Name()) // no-op once moved
	_, werr := f.WriteString(name)
	if cerr := f.Close(); werr == nil {
		werr = cerr
	}
	if werr != nil {
		return "", werr
	}
	from, err := windows.UTF16PtrFromString(f.Name())
	if err != nil {
		return "", err
	}
	to, err := windows.UTF16PtrFromString(filepath.Join(stateDir, desktopTaskFile))
	if err != nil {
		return "", err
	}
	// Without MOVEFILE_REPLACE_EXISTING: a name recorded meanwhile wins.
	if err := windows.MoveFileEx(from, to, 0); err != nil {
		if !errors.Is(err, windows.ERROR_ALREADY_EXISTS) {
			return "", err
		}
		if name = recordedDesktopTask(stateDir); name == "" {
			return "", fmt.Errorf("%s is not a task name", filepath.Join(stateDir, desktopTaskFile))
		}
	}
	return name, nil
}

// launchDesktop starts the helper on the user's desktop (ADR-0016 §3,
// ADR-0017): it refreshes the GUI copy of this binary, (re)registers the
// scheduled task that runs it only while the user is signed in, checks that
// the task runs as this user, and runs it. Windows starts it in the user's own
// session, which a process under
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
	name, err := desktopTaskName(stateDir)
	if err != nil {
		return withCode(codeDesktopTask, err)
	}
	if out, err := schtasks("/create", "/tn", name, "/xml", f.Name(), "/f"); err != nil {
		return withCode(codeDesktopTask, fmt.Errorf("creating the scheduled task %s: %v: %s", name, err, out))
	}
	if err := checkTaskUser(name); err != nil {
		return withCode(codeDesktopTask, err)
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
		tmp, err := createPrivateTemp(stateDir, ".desktop-*.exe")
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

// taskUserID finds the account a task runs as in its XML definition.
var taskUserID = regexp.MustCompile(`<UserId>\s*([^<\s]+)\s*</UserId>`)

// checkTaskUser makes sure the task called name runs as this user, so the
// helper's /run never starts a task that someone else defined under the name.
func checkTaskUser(name string) error {
	out, err := schtasksOutput("/query", "/tn", name, "/xml")
	if err != nil {
		return fmt.Errorf("reading the scheduled task %s: %v: %s", name, err, oneLine(out))
	}
	return checkTaskXMLUser(name, out)
}

// checkTaskXMLUser is checkTaskUser on the task's XML definition.
func checkTaskXMLUser(name string, xml []byte) error {
	m := taskUserID.FindSubmatch(xml)
	if m == nil {
		return fmt.Errorf("the scheduled task %s names no account to run as", name)
	}
	sid, err := windows.StringToSid(string(m[1]))
	if err != nil {
		// A task registered with an account name rather than a SID.
		if sid, _, _, err = windows.LookupSID("", string(m[1])); err != nil {
			return fmt.Errorf("the scheduled task %s runs as an unknown account %s", name, m[1])
		}
	}
	user, err := currentUserSID()
	if err != nil {
		return err
	}
	if !sid.Equals(user) {
		return fmt.Errorf("the scheduled task %s runs as %s, not as this user", name, accountName(sid))
	}
	return nil
}

func desktopTaskExists(stateDir string) bool {
	name := recordedDesktopTask(stateDir)
	if name == "" {
		return false
	}
	_, err := schtasks("/query", "/tn", name)
	return err == nil
}

// deleteDesktopTask deletes stateDir's task, if it has one, and forgets its
// name, so the next launch chooses a new one.
func deleteDesktopTask(stateDir string) error {
	if desktopTaskExists(stateDir) {
		name := recordedDesktopTask(stateDir)
		if out, err := schtasks("/delete", "/tn", name, "/f"); err != nil {
			return fmt.Errorf("deleting the scheduled task %s: %v: %s", name, err, out)
		}
	}
	return removeIfExists(filepath.Join(stateDir, desktopTaskFile))
}

// schtasks runs the system's schtasks.exe and returns its output on one line,
// with what is not ASCII replaced (it prints in the console code page).
func schtasks(args ...string) (string, error) {
	out, err := schtasksOutput(args...)
	return oneLine(out), err
}

// schtasksOutput runs the system's schtasks.exe, by full path so a schtasks
// elsewhere on PATH is never picked up, and returns its output as it is.
func schtasksOutput(args ...string) ([]byte, error) {
	root := os.Getenv("SystemRoot")
	if root == "" {
		root = `C:\Windows`
	}
	cmd := exec.Command(filepath.Join(root, "System32", "schtasks.exe"), args...)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	return cmd.CombinedOutput()
}

func oneLine(out []byte) string {
	text := strings.Map(func(r rune) rune {
		if r < 0x20 || r > 0x7e {
			return ' '
		}
		return r
	}, string(out))
	return strings.Join(strings.Fields(text), " ")
}
