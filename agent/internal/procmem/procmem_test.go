package procmem

import (
	"errors"
	"os"
	"testing"
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
}
