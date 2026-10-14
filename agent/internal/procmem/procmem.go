// Package procmem reads the process table, so the agent can tell the client
// what its sessions hold: how much memory and CPU time a session's tree uses
// (the shell and everything started from it), not just the daemon itself, and
// which program in it is running now.
//
// Each platform lists the processes it can see with their parent, name,
// resident size and CPU time (procmem_*.go); the tree walks are shared. A
// process only counts as a child when it started after its parent, so a
// recycled parent PID never pulls an unrelated process into a tree.
package procmem

import (
	"errors"
	"strings"
	"time"
)

// ErrUnsupported reports a platform where the agent cannot list processes.
var ErrUnsupported = errors.New("procmem: process memory is not available on this system")

// proc is one listed process. start orders processes in time (any unit, only
// compared within one Table); 0 means unknown and skips the check.
type proc struct {
	pid, ppid int
	name      string // the executable's name, without its directory
	rss       uint64 // resident bytes
	cpu       time.Duration
	start     uint64
}

// Table is a snapshot of the processes visible to the agent.
type Table struct {
	byPID    map[int]proc
	children map[int][]int
}

// Snapshot lists the visible processes now.
func Snapshot() (*Table, error) {
	procs, err := list()
	if err != nil {
		return nil, err
	}
	return newTable(procs), nil
}

func newTable(procs []proc) *Table {
	t := &Table{byPID: make(map[int]proc, len(procs)), children: map[int][]int{}}
	for _, p := range procs {
		t.byPID[p.pid] = p
	}
	for _, p := range procs {
		if p.ppid == p.pid {
			continue
		}
		if parent, ok := t.byPID[p.ppid]; ok && startedAfter(p, parent) {
			t.children[p.ppid] = append(t.children[p.ppid], p.pid)
		}
	}
	return t
}

func startedAfter(child, parent proc) bool {
	return child.start == 0 || parent.start == 0 || child.start >= parent.start
}

// TreeBytes is the resident memory of pid and all its descendants, and false
// if pid is not in the snapshot (e.g. it already exited).
func (t *Table) TreeBytes(pid int) (uint64, bool) {
	var total uint64
	ok := t.walk(pid, func(p proc) { total += p.rss })
	return total, ok
}

// TreesBytes is the resident memory of the trees of pids together, each
// process counted once even when one tree holds another, and false if none of
// pids is in the snapshot.
func (t *Table) TreesBytes(pids ...int) (uint64, bool) {
	seen := map[int]bool{}
	var total uint64
	found := false
	for _, pid := range pids {
		found = t.walk(pid, func(p proc) {
			if !seen[p.pid] {
				seen[p.pid] = true
				total += p.rss
			}
		}) || found
	}
	return total, found
}

// TreeCPU is the CPU time used so far by pid and its live descendants, and
// false if pid is not in the snapshot.
func (t *Table) TreeCPU(pid int) (time.Duration, bool) {
	var total time.Duration
	ok := t.walk(pid, func(p proc) { total += p.cpu })
	return total, ok
}

// Name is pid's executable name, or "" if pid is not in the snapshot.
func (t *Table) Name(pid int) string { return t.byPID[pid].name }

// Newest follows pid's youngest child down to a process with none, skipping
// the console hosts Windows starts beside a shell: the program the user most
// likely has in front of them where the terminal cannot tell (ConPTY has no
// foreground process group). It returns pid itself when pid has no children,
// and 0 if pid is not in the snapshot.
func (t *Table) Newest(pid int) int {
	if _, ok := t.byPID[pid]; !ok {
		return 0
	}
	seen := map[int]bool{}
	cur := pid
	for !seen[cur] {
		seen[cur] = true
		next := t.newestChild(cur)
		if next == 0 {
			break
		}
		cur = next
	}
	return cur
}

// ShellDir is the directory the user is in at the terminal whose shell is
// shellPID, or "" when it cannot be told for sure. reported is what the
// terminal learned from the shell itself, "" if nothing: on Windows, the
// directory PowerShell writes from its prompt, as its process directory never
// moves. On Linux it is the shell's process directory.
func (t *Table) ShellDir(shellPID int, reported string) string {
	return shellDir(t, shellPID, reported)
}

// windowsShellDir is [Table.ShellDir] on Windows, with processDir reading a
// process's own directory. It looks at the innermost shell the user started
// at the terminal (the youngest along the [Table.Newest] chain), and only
// trusts cmd.exe's process directory and, for the shell the session started,
// what it reported. Any other shell or remote client inside (a nested
// PowerShell, bash, wsl, ssh…) has a directory the agent cannot see.
func (t *Table) windowsShellDir(shellPID int, reported string, processDir func(int) string) string {
	if t == nil || shellPID <= 0 {
		return ""
	}
	if _, ok := t.byPID[shellPID]; !ok {
		return ""
	}
	inner := shellPID
	seen := map[int]bool{}
	for cur := shellPID; !seen[cur]; {
		seen[cur] = true
		next := t.newestChild(cur)
		if next == 0 {
			break
		}
		if isNestedShell(t.byPID[next].name) {
			inner = next
		}
		cur = next
	}
	name := strings.ToLower(t.byPID[inner].name)
	switch {
	case name == "cmd.exe":
		return driveDir(processDir(inner))
	case inner == shellPID && (name == "powershell.exe" || name == "pwsh.exe"):
		return driveDir(reported)
	}
	return ""
}

// newestChild is pid's youngest child that is not a console host, 0 if none.
func (t *Table) newestChild(pid int) int {
	next, newest := 0, uint64(0)
	for _, c := range t.children[pid] {
		p := t.byPID[c]
		if isConsoleHost(p.name) {
			continue
		}
		if next == 0 || p.start >= newest {
			next, newest = c, p.start
		}
	}
	return next
}

// isNestedShell is a program that, started at the terminal, takes over its
// input with a directory of its own: another shell, or a client to elsewhere.
func isNestedShell(name string) bool {
	switch strings.ToLower(name) {
	case "cmd.exe", "powershell.exe", "pwsh.exe", "bash.exe", "sh.exe", "zsh.exe", "fish.exe",
		"nu.exe", "wsl.exe", "ssh.exe", "telnet.exe", "docker.exe", "kubectl.exe":
		return true
	}
	return false
}

// driveDir is dir when it is a folder on a drive (`C:\Users\u`), without the
// trailing backslash Windows keeps on a process directory except at a drive's
// root, and "" otherwise (empty, a network share, `\\?\` forms).
func driveDir(dir string) string {
	dir = strings.TrimSpace(strings.TrimPrefix(dir, "\ufeff"))
	if len(dir) < 3 || dir[1] != ':' || dir[2] != '\\' {
		return ""
	}
	if c := dir[0] | 0x20; c < 'a' || c > 'z' {
		return ""
	}
	for len(dir) > 3 && dir[len(dir)-1] == '\\' {
		dir = dir[:len(dir)-1]
	}
	return dir
}

func isConsoleHost(name string) bool {
	n := strings.ToLower(name)
	return n == "conhost.exe" || n == "openconsole.exe"
}

// walk calls fn once for pid and each of its descendants.
func (t *Table) walk(pid int, fn func(proc)) bool {
	if _, ok := t.byPID[pid]; !ok {
		return false
	}
	seen := map[int]bool{}
	stack := []int{pid}
	for len(stack) > 0 {
		cur := stack[len(stack)-1]
		stack = stack[:len(stack)-1]
		if seen[cur] {
			continue
		}
		seen[cur] = true
		fn(t.byPID[cur])
		stack = append(stack, t.children[cur]...)
	}
	return true
}
