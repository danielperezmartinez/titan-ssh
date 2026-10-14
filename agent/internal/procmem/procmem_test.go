package procmem

import (
	"errors"
	"os"
	"testing"
	"time"
)

func TestTreeBytesSumsDescendants(t *testing.T) {
	tb := newTable([]proc{
		{pid: 1, ppid: 0, rss: 1, start: 1},
		{pid: 10, ppid: 1, rss: 100, start: 5},  // the agent
		{pid: 20, ppid: 10, rss: 20, start: 6},  // a shell
		{pid: 21, ppid: 20, rss: 5, start: 7},   // a program in it
		{pid: 30, ppid: 10, rss: 30, start: 8},  // another shell
		{pid: 40, ppid: 1, rss: 1000, start: 9}, // unrelated
	})
	if b, ok := tb.TreeBytes(10); !ok || b != 155 {
		t.Fatalf("agent tree = %d, %v; want 155", b, ok)
	}
	if b, ok := tb.TreeBytes(20); !ok || b != 25 {
		t.Fatalf("shell tree = %d, %v; want 25", b, ok)
	}
	if _, ok := tb.TreeBytes(99); ok {
		t.Fatal("a missing pid has no tree")
	}
}

// A process whose parent PID was recycled by a newer process is not that
// process's child.
func TestTreeBytesSkipsRecycledParents(t *testing.T) {
	tb := newTable([]proc{
		{pid: 10, ppid: 1, rss: 100, start: 50}, // reused PID 10, started later
		{pid: 11, ppid: 10, rss: 7, start: 20},  // child of the old PID 10
	})
	if b, _ := tb.TreeBytes(10); b != 100 {
		t.Fatalf("tree = %d; the orphan of the old PID must not count", b)
	}
}

func TestTreeBytesSurvivesCycles(t *testing.T) {
	tb := newTable([]proc{{pid: 1, ppid: 2, rss: 1}, {pid: 2, ppid: 1, rss: 2}})
	if b, ok := tb.TreeBytes(1); !ok || b != 3 {
		t.Fatalf("tree = %d, %v", b, ok)
	}
}

func TestTreeCPUSumsDescendants(t *testing.T) {
	tb := newTable([]proc{
		{pid: 20, ppid: 1, cpu: time.Second, start: 6},
		{pid: 21, ppid: 20, cpu: 2 * time.Second, start: 7},
		{pid: 40, ppid: 1, cpu: time.Hour, start: 9},
	})
	if d, ok := tb.TreeCPU(20); !ok || d != 3*time.Second {
		t.Fatalf("TreeCPU = %v, %v; want 3s", d, ok)
	}
	if _, ok := tb.TreeCPU(99); ok {
		t.Fatal("a missing pid has no tree")
	}
}

// Newest follows the youngest child at each level, and ignores the console
// hosts Windows runs next to a shell.
func TestNewestFollowsTheYoungestChild(t *testing.T) {
	tb := newTable([]proc{
		{pid: 20, ppid: 1, name: "cmd.exe", start: 6},
		{pid: 21, ppid: 20, name: "node.exe", start: 7},     // an older job
		{pid: 22, ppid: 20, name: "pwsh.exe", start: 8},     // the shell started last
		{pid: 23, ppid: 20, name: "conhost.exe", start: 12}, // newer, but a console host
		{pid: 24, ppid: 22, name: "claude.exe", start: 10},
	})
	if got := tb.Newest(20); got != 24 {
		t.Fatalf("Newest = %d (%s); want 24", got, tb.Name(got))
	}
	if tb.Name(24) != "claude.exe" {
		t.Fatalf("Name = %q", tb.Name(24))
	}
	if got := tb.Newest(24); got != 24 {
		t.Fatalf("a leaf is its own newest, got %d", got)
	}
	if got := tb.Newest(99); got != 0 {
		t.Fatalf("a missing pid gives 0, got %d", got)
	}
}

// On Windows the directory comes from the innermost shell at the terminal:
// cmd.exe's process directory, PowerShell's own report only for the shell the
// session started, and nothing for any other shell or remote client.
func TestWindowsShellDirFollowsTheInnermostShell(t *testing.T) {
	dirs := map[int]string{20: `C:\Users\u\`, 30: `D:\work`, 50: `C:\`}
	processDir := func(pid int) string { return dirs[pid] }
	cases := []struct {
		name     string
		procs    []proc
		reported string
		want     string
	}{
		{"cmd alone", []proc{{pid: 20, name: "cmd.exe", start: 1}}, "", `C:\Users\u`},
		{"cmd running a program", []proc{
			{pid: 20, name: "cmd.exe", start: 1},
			{pid: 21, ppid: 20, name: "conhost.exe", start: 2},
			{pid: 22, ppid: 20, name: "node.exe", start: 3},
		}, "", `C:\Users\u`},
		{"powershell reports", []proc{{pid: 20, name: "pwsh.exe", start: 1}}, "E:\\src\\app\r\n", `E:\src\app`},
		{"powershell in another provider", []proc{{pid: 20, name: "powershell.exe", start: 1}}, "", ""},
		{"powershell's process dir is never used", []proc{{pid: 20, name: "PowerShell.exe", start: 1}}, `\\server\share`, ""},
		{"cmd inside powershell", []proc{
			{pid: 20, name: "powershell.exe", start: 1},
			{pid: 30, ppid: 20, name: "cmd.exe", start: 2},
		}, `C:\elsewhere`, `D:\work`},
		{"powershell inside cmd", []proc{
			{pid: 20, name: "cmd.exe", start: 1},
			{pid: 30, ppid: 20, name: "powershell.exe", start: 2},
		}, "", ""},
		{"ssh inside cmd", []proc{
			{pid: 20, name: "cmd.exe", start: 1},
			{pid: 30, ppid: 20, name: "ssh.exe", start: 2},
		}, "", ""},
		{"a tool's cmd under a program", []proc{
			{pid: 20, name: "pwsh.exe", start: 1},
			{pid: 40, ppid: 20, name: "claude.exe", start: 2},
			{pid: 50, ppid: 40, name: "cmd.exe", start: 3},
		}, `C:\x`, `C:\`},
		{"another shell at the root", []proc{{pid: 20, name: "bash.exe", start: 1}}, `C:\x`, ""},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := newTable(c.procs).windowsShellDir(20, c.reported, processDir); got != c.want {
				t.Fatalf("dir = %q; want %q", got, c.want)
			}
		})
	}
	if got := newTable(nil).windowsShellDir(20, `C:\x`, processDir); got != "" {
		t.Fatalf("a shell missing from the snapshot gives %q", got)
	}
}

func TestDriveDirKeepsOnlyFoldersOnADrive(t *testing.T) {
	for in, want := range map[string]string{
		`C:\Users\u\`:    `C:\Users\u`,
		`c:\`:            `c:\`,
		"\ufeffC:\\src":  `C:\src`,
		`\\server\share`: "",
		`\\?\C:\long`:    "",
		`HKCU:\`:         "",
		`C:relative`:     "",
		"":               "",
		`1:\x`:           "",
		`D:\\`:           `D:\`,
	} {
		if got := driveDir(in); got != want {
			t.Errorf("driveDir(%q) = %q; want %q", in, got, want)
		}
	}
}

// The real snapshot sees this test process and gives it a resident size.
func TestSnapshotSeesThisProcess(t *testing.T) {
	tb, err := Snapshot()
	if errors.Is(err, ErrUnsupported) {
		t.Skip(err)
	}
	if err != nil {
		t.Fatal(err)
	}
	b, ok := tb.TreeBytes(os.Getpid())
	if !ok || b == 0 {
		t.Fatalf("own tree = %d, %v", b, ok)
	}
	if tb.Name(os.Getpid()) == "" {
		t.Fatal("own process has no name")
	}
}
