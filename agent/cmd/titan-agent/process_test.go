package main

import (
	"errors"
	"io"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"
)

// agentMainEnv makes the test binary run main() instead of the tests, so these
// tests drive the real front, daemon and --stop as separate processes (the
// front launches the daemon from os.Executable(), i.e. this binary).
const agentMainEnv = "TITAN_AGENT_TEST_MAIN"

func TestMain(m *testing.M) {
	if os.Getenv(agentMainEnv) == "1" {
		main()
		os.Exit(0)
	}
	os.Exit(m.Run())
}

func agentCmd(t *testing.T, args ...string) *exec.Cmd {
	t.Helper()
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(exe, args...)
	cmd.Env = append(os.Environ(), agentMainEnv+"=1")
	return cmd
}

// stopAgent runs --stop on dir. Registered as a cleanup after t.TempDir, so it
// runs first: Windows cannot remove the dir while the daemon holds its lock.
func stopAgent(t *testing.T, dir string) {
	t.Helper()
	if out, err := agentCmd(t, "--stop", "--state-dir", dir).CombinedOutput(); err != nil {
		t.Errorf("--stop failed: %v: %s", err, out)
	}
}

// front is a running front process driven over its stdio.
type front struct {
	cmd   *exec.Cmd
	stdin io.WriteCloser
	out   *frameReader
}

func startFront(t *testing.T, dir string) *front {
	t.Helper()
	cmd := agentCmd(t, "--state-dir", dir, "--session", "p1")
	stdin, err := cmd.StdinPipe()
	if err != nil {
		t.Fatal(err)
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	return &front{cmd: cmd, stdin: stdin, out: &frameReader{r: stdout}}
}

// close ends the client side, as a dropped SSH channel would, and waits for
// the front to exit.
func (f *front) close(t *testing.T) {
	t.Helper()
	f.stdin.Close()
	if err := waitExit(t, f.cmd, 10*time.Second); err != nil {
		t.Fatalf("front exited with %v", err)
	}
}

func waitExit(t *testing.T, cmd *exec.Cmd, timeout time.Duration) error {
	t.Helper()
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case err := <-done:
		return err
	case <-time.After(timeout):
		_ = cmd.Process.Kill()
		t.Fatalf("%v did not exit", cmd.Args)
		return nil
	}
}

func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

// End to end with a real PTY: the first front launches the daemon, which
// creates the session; after the front goes away, a second front re-attaches
// to the same live session; --stop then ends the daemon and its state.
func TestFrontLaunchesDaemonAndReattaches(t *testing.T) {
	dir := testStateDir(t)
	t.Cleanup(func() { stopAgent(t, dir) })

	f1 := startFront(t, dir)
	if ok := hello(t, f1.stdin, f1.out, "proc-session"); !ok.Created {
		t.Fatal("the first attach should create the session")
	}
	f1.close(t)

	f2 := startFront(t, dir)
	if ok := hello(t, f2.stdin, f2.out, "proc-session"); ok.Created {
		t.Fatal("a second front must re-attach to the live session")
	}
	f2.close(t)

	if out, err := agentCmd(t, "--stop", "--state-dir", dir).CombinedOutput(); err != nil {
		t.Fatalf("--stop: %v: %s", err, out)
	}
	if held, err := lockHeld(dir); err != nil || held {
		t.Fatalf("the daemon should be gone: held=%v err=%v", held, err)
	}
	if _, err := os.Stat(filepath.Join(dir, stateFileName)); !errors.Is(err, fs.ErrNotExist) {
		t.Fatalf("--stop should remove the state file, got %v", err)
	}
}

// Never two daemons: a second --daemon exits 0 at once while the first keeps
// serving, and killing the first (as a crash would) frees the lock.
func TestKilledDaemonReleasesLock(t *testing.T) {
	dir := testStateDir(t)
	first := agentCmd(t, "--daemon", "--state-dir", dir)
	if err := first.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if first.ProcessState == nil {
			_ = first.Process.Kill()
			_ = first.Wait()
		}
	})
	waitFor(t, "the daemon's state file", func() bool { _, err := readState(dir); return err == nil })

	second := agentCmd(t, "--daemon", "--state-dir", dir)
	if err := second.Start(); err != nil {
		t.Fatal(err)
	}
	if err := waitExit(t, second, 5*time.Second); err != nil {
		t.Fatalf("the second daemon must exit 0, got %v", err)
	}

	st, err := readState(dir)
	if err != nil {
		t.Fatal(err)
	}
	if st.PID != first.Process.Pid {
		t.Fatalf("state file belongs to pid %d, want the first daemon %d", st.PID, first.Process.Pid)
	}
	conn, err := dialDaemon(st)
	if err != nil {
		t.Fatalf("the first daemon must keep serving: %v", err)
	}
	conn.Close()

	if err := first.Process.Kill(); err != nil {
		t.Fatal(err)
	}
	_ = first.Wait()
	waitFor(t, "the lock to be released", func() bool { held, err := lockHeld(dir); return err == nil && !held })
	l, err := lockDaemon(dir)
	if err != nil {
		t.Fatalf("a new daemon must get the lock: %v", err)
	}
	l.Close()
}

// --stop with nothing running succeeds and drops a stale state file.
func TestStopWithoutDaemonRemovesStaleState(t *testing.T) {
	dir := testStateDir(t)
	if err := writeState(dir, validState()); err != nil {
		t.Fatal(err)
	}
	stopAgent(t, dir)
	if _, err := os.Stat(filepath.Join(dir, stateFileName)); !errors.Is(err, fs.ErrNotExist) {
		t.Fatalf("stale state file should be gone, got %v", err)
	}
}
