package main

import (
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

func TestDesktopTaskNameIsChosenOnceAndKept(t *testing.T) {
	dir := testStateDir(t)
	if got := recordedDesktopTask(dir); got != "" {
		t.Fatalf("a fresh dir has task %q", got)
	}
	first, err := desktopTaskName(dir)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(first, desktopTaskPrefix) || len(first) <= len(desktopTaskPrefix)+16 {
		t.Fatalf("task name %q has no random part", first)
	}
	again, err := desktopTaskName(dir)
	if err != nil || again != first || recordedDesktopTask(dir) != first {
		t.Fatalf("second call = %q, %v; want the recorded %q", again, err, first)
	}
	other, err := desktopTaskName(testStateDir(t))
	if err != nil || other == first {
		t.Fatalf("another state dir got %q, %v: names must differ", other, err)
	}
}

func TestDesktopTaskNameIgnoresWhatIsNotOne(t *testing.T) {
	for _, bad := range []string{
		`\Microsoft\Windows\Defrag\ScheduledDefrag`,
		"titan-ssh-desktop-",
		"titan-ssh-desktop-a b",
		"titan-ssh-desktop-a\\b",
		"someone-else",
		"titan-ssh-desktop-" + strings.Repeat("x", 200),
	} {
		dir := testStateDir(t)
		if err := os.WriteFile(filepath.Join(dir, desktopTaskFile), []byte(bad), 0o600); err != nil {
			t.Fatal(err)
		}
		if got := recordedDesktopTask(dir); got != "" {
			t.Errorf("%q read back as task %q", bad, got)
		}
	}
}

func TestTaskMustRunAsThisUser(t *testing.T) {
	user, err := currentUserSID()
	if err != nil {
		t.Fatal(err)
	}
	account := accountName(user)
	task := func(userID string) []byte {
		return []byte("<?xml version=\"1.0\" encoding=\"UTF-16\"?>\r\r\n<Task><Principals><Principal id=\"Author\">\r\r\n" +
			"      <UserId>" + userID + "</UserId>\r\r\n      <LogonType>InteractiveToken</LogonType></Principal></Principals></Task>")
	}
	for _, own := range []string{user.String(), account} {
		if err := checkTaskXMLUser("t", task(own)); err != nil {
			t.Errorf("a task that runs as %s: %v", own, err)
		}
	}
	for _, other := range []string{"S-1-5-18", "S-1-5-21-1-2-3-1001", `NT AUTHORITY\SYSTEM`, "no-such-account-titan"} {
		if err := checkTaskXMLUser("t", task(other)); err == nil {
			t.Errorf("a task that runs as %s must be refused", other)
		}
	}
	if err := checkTaskXMLUser("t", []byte("<Task></Task>")); err == nil {
		t.Error("a task with no UserId must be refused")
	}
}

func TestRacingFrontsAgreeOnTheTaskName(t *testing.T) {
	dir := testStateDir(t)
	names := make([]string, 8)
	var wg sync.WaitGroup
	for i := range names {
		wg.Add(1)
		go func() {
			defer wg.Done()
			name, err := desktopTaskName(dir)
			if err != nil {
				t.Error(err)
			}
			names[i] = name
		}()
	}
	wg.Wait()
	for _, n := range names {
		if n != names[0] {
			t.Fatalf("fronts chose different names: %q", names)
		}
	}
	if leftovers, _ := filepath.Glob(filepath.Join(dir, ".desktop-task-*")); len(leftovers) != 0 {
		t.Fatalf("temp files left behind: %v", leftovers)
	}
}
