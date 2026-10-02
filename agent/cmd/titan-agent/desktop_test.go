package main

import (
	"bufio"
	"bytes"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
	"unicode/utf16"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/protocol"
)

// recordingInjector counts the moves it gets.
type recordingInjector struct {
	mu    sync.Mutex
	moves int
}

func (r *recordingInjector) Move(dx, dy int) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.moves++
	return nil
}
func (r *recordingInjector) Button(uint8, bool) error       { return nil }
func (r *recordingInjector) Scroll(int, int) error          { return nil }
func (r *recordingInjector) Text(string) error              { return nil }
func (r *recordingInjector) Key(uint16, uint8, uint8) error { return nil }
func (r *recordingInjector) Blocked() bool                  { return false }

func (r *recordingInjector) count() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.moves
}

// fakeDesktopTask keeps a test away from the real scheduled task.
func fakeDesktopTask(t *testing.T) (deleted *bool) {
	oldExists, oldDelete := taskExists, taskDelete
	deleted = new(bool)
	taskExists = func(string) bool { return !*deleted }
	taskDelete = func(string) error { *deleted = true; return nil }
	t.Cleanup(func() { taskExists, taskDelete = oldExists, oldDelete })
	return deleted
}

// fastDesktopWaits shortens the front's waits for one test.
func fastDesktopWaits(t *testing.T) {
	oldWait, oldGap, oldRetry := desktopWait, relaunchGap, desktopRetry
	desktopWait, relaunchGap, desktopRetry = 2*time.Second, 200*time.Millisecond, 10*time.Millisecond
	t.Cleanup(func() { desktopWait, relaunchGap, desktopRetry = oldWait, oldGap, oldRetry })
}

// startTestHelper runs a desktop helper in-process, as the scheduled task
// would, and returns a function that waits for it to stop.
func startTestHelper(t *testing.T, dir string, inj *recordingInjector) (wait func()) {
	t.Helper()
	h, err := startDesktop(dir, inj, 7)
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		defer h.close()
		h.serve()
	}()
	t.Cleanup(func() { h.ln.Close(); <-done })
	return func() { <-done }
}

func TestInputFrontLaunchesTheHelperAndReachesTheInjector(t *testing.T) {
	fastDesktopWaits(t)
	dir := testStateDir(t)
	inj := &recordingInjector{}
	launches := 0
	conn, err := connectDesktop(dir, func() error {
		launches++
		startTestHelper(t, dir, inj)
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if launches != 1 {
		t.Fatalf("launched %d times, want 1", launches)
	}
	dec := &protocol.Decoder{}
	buf := make([]byte, 64)
	_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	n, err := conn.Read(buf)
	frames, _ := dec.Feed(buf[:n])
	if err != nil || len(frames) != 1 || frames[0].Type != protocol.TypeInputReady {
		t.Fatalf("first frame = %v, %v; want INPUT_READY", frames, err)
	}
	for range 3 {
		if _, err := conn.Write(protocol.Encode(protocol.Frame{Type: protocol.TypePointerMove, DX: 1})); err != nil {
			t.Fatal(err)
		}
	}
	deadline := time.Now().Add(2 * time.Second)
	for inj.count() != 3 {
		if time.Now().After(deadline) {
			t.Fatalf("the injector got %d moves, want 3", inj.count())
		}
		time.Sleep(5 * time.Millisecond)
	}

	// A second front finds the running helper and launches nothing.
	conn2, err := connectDesktop(dir, func() error { t.Error("launched again"); return nil })
	if err != nil {
		t.Fatal(err)
	}
	conn2.Close()
}

func TestInputFrontGivesUpWithNoDesktop(t *testing.T) {
	fastDesktopWaits(t)
	dir := testStateDir(t)
	start := time.Now()
	_, err := connectDesktop(dir, func() error { return nil }) // the task never runs
	var ae *agentError
	if !errors.As(err, &ae) || ae.Code != codeNoDesktop {
		t.Fatalf("err = %v, want %s", err, codeNoDesktop)
	}
	if time.Since(start) < desktopWait {
		t.Fatalf("gave up after %v, before desktopWait", time.Since(start))
	}
}

func TestInputFrontReportsWhyTheHelperFailed(t *testing.T) {
	fastDesktopWaits(t)
	dir := testStateDir(t)
	_, err := connectDesktop(dir, func() error {
		_ = recordDesktopError(dir, withCode(codeNoDesktop, errors.New("SendInput is not available")))
		return nil
	})
	if err == nil || !strings.Contains(err.Error(), "SendInput is not available") {
		t.Fatalf("err = %v, want the helper's own error", err)
	}
}

// A helper of an older version speaks only the first handshake: the front
// stops it over that one and launches the current version, and never sends it
// input.
func TestInputFrontReplacesAnOlderHelper(t *testing.T) {
	fastDesktopWaits(t)
	dir := testStateDir(t)
	lock, err := lockNamed(dir, desktopLockName)
	if err != nil {
		t.Fatal(err)
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	token := bytes.Repeat([]byte{9}, tokenLen)
	st := validState()
	st.Agent, st.Port, st.Token = "0.1.0-beta.10", ln.Addr().(*net.TCPAddr).Port, strings.Repeat("09", tokenLen)
	if err := writeStateFile(dir, desktopStateName, st); err != nil {
		t.Fatal(err)
	}
	var mu sync.Mutex
	var magics []string
	stopped := make(chan struct{})
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			pre := make([]byte, magicLen+tokenLen)
			if _, err := io.ReadFull(c, pre); err == nil {
				mu.Lock()
				magics = append(magics, string(pre[:magicLen]))
				mu.Unlock()
				if string(pre[:magicLen]) == legacyDesktopControlMagic && bytes.Equal(pre[magicLen:], token) {
					_, _ = c.Write([]byte(legacyDesktopControlAck))
					line, _ := bufio.NewReader(c).ReadBytes('\n')
					if strings.Contains(string(line), desktopOpStop) {
						writeDesktopReply(c, desktopReply{})
						c.Close()
						ln.Close()
						lock.Close()
						close(stopped)
						return
					}
				}
			}
			c.Close()
		}
	}()
	t.Cleanup(func() { ln.Close(); lock.Close() })

	conn, err := connectDesktop(dir, func() error {
		startTestHelper(t, dir, &recordingInjector{})
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	conn.Close()
	<-stopped
	mu.Lock()
	defer mu.Unlock()
	for _, m := range magics {
		if m == legacyDesktopMagic {
			t.Fatal("input must not go to the older helper")
		}
	}
	if got, err := readStateFile(dir, desktopStateName); err != nil || got.Agent != version {
		t.Fatalf("desktop.json = %+v, %v; want version %s", got, err, version)
	}
}

func TestInputFrontReplacesAHelperOfAnotherVersion(t *testing.T) {
	fastDesktopWaits(t)
	dir := testStateDir(t)
	old := version
	version = "0.0.1-old"
	stopped := startTestHelper(t, dir, &recordingInjector{})
	version = old

	launched := false
	conn, err := connectDesktop(dir, func() error {
		launched = true
		startTestHelper(t, dir, &recordingInjector{})
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	conn.Close()
	stopped() // the old helper was asked to stop
	if !launched {
		t.Fatal("the current version was not launched")
	}
	st, err := readStateFile(dir, desktopStateName)
	if err != nil || st.Agent != version {
		t.Fatalf("desktop.json = %+v, %v; want version %s", st, err, version)
	}
}

func TestRemoveDesktopStopsTheHelperAndCleansUp(t *testing.T) {
	deleted := fakeDesktopTask(t)
	dir := testStateDir(t)
	stopped := startTestHelper(t, dir, &recordingInjector{})
	copyPath := filepath.Join(dir, "desktop-0.0.1.exe")
	if err := os.WriteFile(copyPath, []byte("MZ"), 0o600); err != nil {
		t.Fatal(err)
	}
	if r := queryDesktop(dir); r.State != stateRunning || r.Session != 7 || r.Agent != version || !r.Task {
		t.Fatalf("status before = %+v, want running in session 7 with its task", r)
	}
	if err := removeDesktop(dir); err != nil {
		t.Fatal(err)
	}
	stopped()
	if !*deleted {
		t.Error("the scheduled task was not deleted")
	}
	if r := queryDesktop(dir); r.State != stateStopped || r.Task {
		t.Fatalf("status after = %+v, want stopped with no task", r)
	}
	for _, name := range []string{desktopStateName, filepath.Base(copyPath)} {
		if _, err := os.Stat(filepath.Join(dir, name)); !errors.Is(err, os.ErrNotExist) {
			t.Errorf("%s still exists (%v)", name, err)
		}
	}
	// Nothing left to remove is fine.
	if err := removeDesktop(dir); err != nil {
		t.Fatal(err)
	}
}

func TestHelperIsSingleInstance(t *testing.T) {
	dir := testStateDir(t)
	startTestHelper(t, dir, &recordingInjector{})
	if _, err := startDesktop(dir, &recordingInjector{}, 1); !errors.Is(err, errLocked) {
		t.Fatalf("second helper: err = %v, want errLocked", err)
	}
}

func TestHelperRejectsAWrongToken(t *testing.T) {
	dir := testStateDir(t)
	startTestHelper(t, dir, &recordingInjector{})
	st, err := readStateFile(dir, desktopStateName)
	if err != nil {
		t.Fatal(err)
	}
	st.Token = strings.Repeat("00", tokenLen)
	if _, err := dialWith(st, desktopMagic); !errors.Is(err, errRejected) {
		t.Fatalf("dial with a wrong token: err = %v, want errRejected", err)
	}
	// The daemon's magic is no key to the helper either.
	st, _ = readStateFile(dir, desktopStateName)
	if _, err := dialWith(st, preambleMagic); !errors.Is(err, errRejected) {
		t.Fatalf("dial with the daemon's magic: err = %v, want errRejected", err)
	}
}

// minimalPE is the smallest buffer peSubsystemOffset accepts, with the given
// optional-header magic and subsystem.
func minimalPE(magic, subsystem uint16) []byte {
	const pe = 0x80
	b := make([]byte, pe+4+20+70)
	b[0], b[1] = 'M', 'Z'
	binary.LittleEndian.PutUint32(b[0x3c:], pe)
	copy(b[pe:], "PE\x00\x00")
	opt := pe + 4 + 20
	binary.LittleEndian.PutUint16(b[opt:], magic)
	binary.LittleEndian.PutUint16(b[opt+68:], subsystem)
	return b
}

func TestWithGUISubsystemChangesOnlyTheSubsystem(t *testing.T) {
	for _, magic := range []uint16{0x10b, 0x20b} {
		in := minimalPE(magic, peSubsystemConsole)
		out, err := withGUISubsystem(in)
		if err != nil {
			t.Fatal(err)
		}
		off := 0x80 + 4 + 20 + 68
		if got := binary.LittleEndian.Uint16(out[off:]); got != peSubsystemGUI {
			t.Fatalf("subsystem = %d, want GUI", got)
		}
		diff := 0
		for i := range in {
			if in[i] != out[i] {
				diff++
			}
		}
		if diff != 1 || in[off] != peSubsystemConsole {
			t.Fatalf("%d bytes changed (or the input was modified), want exactly 1", diff)
		}
	}
	if _, err := withGUISubsystem([]byte("#!/bin/sh\n")); err == nil {
		t.Error("a non-PE file was accepted")
	}
	if _, err := withGUISubsystem(minimalPE(0x10b, 10)); err == nil {
		t.Error("an EFI subsystem was accepted")
	}
}

func TestWithGUISubsystemOnThisBinary(t *testing.T) {
	self, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(self)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.HasPrefix(data, []byte("MZ")) {
		t.Skip("the test binary is not a Windows executable")
	}
	if _, err := withGUISubsystem(data); err != nil {
		t.Fatal(err)
	}
}

func TestDesktopTaskXML(t *testing.T) {
	raw := desktopTaskXML(`C:\Users\A & B\AppData\Local\titan-ssh\desktop-1.0.0.exe`,
		[]string{"--desktop", "--state-dir", `C:\Users\A & B\AppData\Local\titan-ssh`})
	if !bytes.HasPrefix(raw, []byte{0xff, 0xfe}) || len(raw)%2 != 0 {
		t.Fatal("not UTF-16LE with a byte order mark")
	}
	units := make([]uint16, (len(raw)-2)/2)
	for i := range units {
		units[i] = binary.LittleEndian.Uint16(raw[2+2*i:])
	}
	doc := string(utf16.Decode(units))
	for _, want := range []string{
		`encoding="UTF-16"`,
		"<LogonType>InteractiveToken</LogonType>",
		"<RunLevel>LeastPrivilege</RunLevel>",
		"<ExecutionTimeLimit>PT0S</ExecutionTimeLimit>",
		"<DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>",
		"<MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>",
		`<Command>C:\Users\A &amp; B\AppData\Local\titan-ssh\desktop-1.0.0.exe</Command>`,
		`<Arguments>--desktop --state-dir &#34;C:\Users\A &amp; B\AppData\Local\titan-ssh&#34;</Arguments>`,
	} {
		if !strings.Contains(doc, want) {
			t.Errorf("task XML lacks %s", want)
		}
	}
}

func TestWindowsArgQuotesLikeCommandLineToArgv(t *testing.T) {
	cases := map[string]string{
		"--desktop":          "--desktop",
		"":                   `""`,
		`C:\a b\c`:           `"C:\a b\c"`,
		`C:\a b\`:            `"C:\a b\\"`,
		`say "hi"`:           `"say \"hi\""`,
		`a\"b c`:             `"a\\\"b c"`,
		`C:\no\spaces\here\`: `C:\no\spaces\here\`,
	}
	for in, want := range cases {
		if got := windowsArg(in); got != want {
			t.Errorf("windowsArg(%q) = %s, want %s", in, got, want)
		}
	}
}
