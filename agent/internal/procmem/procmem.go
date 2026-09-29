// Package procmem measures the resident memory of process trees, so the agent
// can tell the client how much its sessions weigh (the shell and everything
// started from it), not just the daemon itself.
//
// Each platform lists the processes it can see with their parent and resident
// size (procmem_*.go); the tree walk is shared. A process only counts as a
// child when it started after its parent, so a recycled parent PID never pulls
// an unrelated process into a tree.
package procmem

import "errors"

// ErrUnsupported reports a platform where the agent cannot list processes.
var ErrUnsupported = errors.New("procmem: process memory is not available on this system")

// proc is one listed process. start orders processes in time (any unit, only
// compared within one Table); 0 means unknown and skips the check.
type proc struct {
	pid, ppid int
	rss       uint64 // resident bytes
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
	if _, ok := t.byPID[pid]; !ok {
		return 0, false
	}
	var total uint64
	seen := map[int]bool{}
	stack := []int{pid}
	for len(stack) > 0 {
		cur := stack[len(stack)-1]
		stack = stack[:len(stack)-1]
		if seen[cur] {
			continue
		}
		seen[cur] = true
		total += t.byPID[cur].rss
		stack = append(stack, t.children[cur]...)
	}
	return total, true
}
