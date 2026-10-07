package main

import (
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"unicode/utf8"
)

// fakeLaunch replaces launchProgram for one test and records what it gets.
func fakeLaunch(t *testing.T, pid int, err error) func() []runRequest {
	t.Helper()
	old := launchProgram
	var mu sync.Mutex
	var got []runRequest
	launchProgram = func(req runRequest) (int, error) {
		mu.Lock()
		defer mu.Unlock()
		got = append(got, req)
		return pid, err
	}
	t.Cleanup(func() { launchProgram = old })
	return func() []runRequest {
		mu.Lock()
		defer mu.Unlock()
		return append([]runRequest(nil), got...)
	}
}

// testProgram creates an empty file that stands for a program.
func testProgram(t *testing.T, name string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), name)
	if err := os.WriteFile(path, nil, 0o700); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestValidateRun(t *testing.T) {
	program := testProgram(t, "tool.exe")
	dir := t.TempDir()
	cases := []struct {
		name string
		req  runRequest
		want string // part of the error, "" for none
	}{
		{"ok", runRequest{Program: program, Args: []string{"-a", "b c"}, Dir: dir}, ""},
		{"no dir", runRequest{Program: program}, ""},
		{"empty program", runRequest{}, "no program"},
		{"relative program", runRequest{Program: "tool.exe"}, "not an absolute path"},
		{"missing program", runRequest{Program: filepath.Join(dir, "missing.exe")}, "cannot be found"},
		{"directory as program", runRequest{Program: dir}, "is a directory"},
		{"batch file", runRequest{Program: testProgram(t, "run.BAT")}, "batch file"},
		{"cmd file", runRequest{Program: testProgram(t, "run.cmd")}, "batch file"},
		{"batch file behind a trailing dot", runRequest{Program: program[:len(program)-len("tool.exe")] + "run.bat. "}, "batch file"},
		{"data stream", runRequest{Program: program + ":hidden.bat"}, "data stream"},
		{"NUL in an argument", runRequest{Program: program, Args: []string{"a\x00b"}}, "NUL"},
		{"relative dir", runRequest{Program: program, Dir: "sub"}, "not an absolute path"},
		{"missing dir", runRequest{Program: program, Dir: filepath.Join(dir, "missing")}, "does not exist"},
		{"file as dir", runRequest{Program: program, Dir: program}, "does not exist"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			err := validateRun(c.req)
			switch {
			case c.want == "" && err != nil:
				t.Fatalf("err = %v, want none", err)
			case c.want != "" && (err == nil || !strings.Contains(err.Error(), c.want)):
				t.Fatalf("err = %v, want one about %q", err, c.want)
			}
		})
	}
}

func TestDesktopRunStartsTheProgramAndRecordsIt(t *testing.T) {
	fastDesktopWaits(t)
	dir := testStateDir(t)
	launched := fakeLaunch(t, 4321, nil)
	startTestHelper(t, dir, &recordingInjector{})
	program := testProgram(t, "tool.exe")
	workDir := t.TempDir()

	conn, err := connectDesktopWith(dir, desktopRunMagic, func() error { t.Error("launched a helper"); return nil })
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	reply, err := runExchange(conn, runRequest{Program: program, Args: []string{"--flag", "two words"}, Dir: workDir})
	if err != nil || reply.PID != 4321 {
		t.Fatalf("reply = %+v, %v; want PID 4321", reply, err)
	}
	got := launched()
	if len(got) != 1 || got[0].Program != program || len(got[0].Args) != 2 || got[0].Args[1] != "two words" || got[0].Dir != workDir {
		t.Fatalf("launched %+v", got)
	}
	runs := recentRuns(dir, desktopRunsReported)
	if len(runs) != 1 || runs[0].Program != program || runs[0].PID != 4321 || runs[0].Error != "" || runs[0].TimeMs == 0 {
		t.Fatalf("recorded %+v", runs)
	}
	if r := queryDesktop(dir); len(r.Runs) != 1 {
		t.Fatalf("--status reports %d runs, want 1", len(r.Runs))
	}
}

func TestDesktopRunRefusesAnInvalidRequestWithoutLaunching(t *testing.T) {
	fastDesktopWaits(t)
	dir := testStateDir(t)
	launched := fakeLaunch(t, 1, nil)
	startTestHelper(t, dir, &recordingInjector{})
	conn, err := connectDesktopWith(dir, desktopRunMagic, func() error { return nil })
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_, err = runExchange(conn, runRequest{Program: testProgram(t, "setup.cmd")})
	if err == nil || !strings.Contains(err.Error(), "batch file") {
		t.Fatalf("err = %v, want the batch file refused", err)
	}
	if n := len(launched()); n != 0 {
		t.Fatalf("launched %d programs, want none", n)
	}
	// A refused request is recorded as well.
	if runs := recentRuns(dir, desktopRunsReported); len(runs) != 1 || runs[0].Error == "" {
		t.Fatalf("recorded %+v, want the refusal", runs)
	}
}

func TestDesktopRunReportsALaunchFailure(t *testing.T) {
	fastDesktopWaits(t)
	dir := testStateDir(t)
	fakeLaunch(t, 0, errors.New("needs an administrator"))
	startTestHelper(t, dir, &recordingInjector{})
	conn, err := connectDesktopWith(dir, desktopRunMagic, func() error { return nil })
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if _, err := runExchange(conn, runRequest{Program: testProgram(t, "admin.exe")}); err == nil || !strings.Contains(err.Error(), "administrator") {
		t.Fatalf("err = %v, want the launch failure", err)
	}
}

// The control channel never starts a program, and the helper takes no
// connection that opens the old way, with the raw token, whatever the magic.
func TestDesktopRunIsOnlyReachableOverItsOwnConnection(t *testing.T) {
	fastDesktopWaits(t)
	dir := testStateDir(t)
	launched := fakeLaunch(t, 1, nil)
	startTestHelper(t, dir, &recordingInjector{})
	st, err := readStateFile(dir, desktopStateName)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := desktopRequest(st, "run"); err == nil || !strings.Contains(err.Error(), "unknown op") {
		t.Fatalf("control op run: err = %v, want unknown op", err)
	}
	token, err := st.token()
	if err != nil {
		t.Fatal(err)
	}
	program := testProgram(t, "tool.exe")
	for _, magic := range []string{"TTNADSK1", "TTNADCT1", "TTNADRN1", desktopRunMagic} {
		conn, err := dialLoopback(st.Port)
		if err != nil {
			t.Fatal(err)
		}
		req, _ := json.Marshal(runRequest{Program: program})
		_, _ = conn.Write(append(append([]byte(magic), token...), append(req, '\n')...))
		reply, _ := io.ReadAll(conn)
		conn.Close()
		if strings.Contains(string(reply), `"pid"`) {
			t.Fatalf("the raw token under %s started a program: %q", magic, reply)
		}
	}
	if n := len(launched()); n != 0 {
		t.Fatalf("launched %d programs, want none", n)
	}
}

func TestDesktopRunLogIsTrimmed(t *testing.T) {
	dir := testStateDir(t)
	h := &desktopHelper{dir: dir}
	for i := range desktopRunTrim + 1 {
		h.recordRun(desktopRun{TimeMs: int64(i + 1), Program: "p"})
	}
	if n := len(readRunLines(dir)); n != desktopRunKeep {
		t.Fatalf("the log holds %d records, want %d", n, desktopRunKeep)
	}
	runs := recentRuns(dir, 3)
	if len(runs) != 3 || runs[0].TimeMs != desktopRunTrim+1 || runs[2].TimeMs != desktopRunTrim-1 {
		t.Fatalf("recent runs = %+v, want the newest first", runs)
	}
}

func TestDesktopRunRecordsAreClipped(t *testing.T) {
	fastDesktopWaits(t)
	dir := testStateDir(t)
	fakeLaunch(t, 5, nil)
	startTestHelper(t, dir, &recordingInjector{})
	conn, err := connectDesktopWith(dir, desktopRunMagic, func() error { return nil })
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	args := make([]string, recordArgsMax+5)
	for i := range args {
		args[i] = strings.Repeat("é", recordFieldMax)
	}
	if _, err := runExchange(conn, runRequest{Program: testProgram(t, "tool.exe"), Args: args}); err != nil {
		t.Fatal(err)
	}
	run := recentRuns(dir, 1)[0]
	if len(run.Args) != recordArgsMax+1 || run.Args[recordArgsMax] != "… (5 more)" {
		t.Fatalf("recorded %d args, last %q", len(run.Args), run.Args[len(run.Args)-1])
	}
	if a := run.Args[0]; len(a) > recordFieldMax+len("…") || !strings.HasSuffix(a, "…") || !utf8.ValidString(a) {
		t.Fatalf("first arg recorded as %d bytes: %q", len(a), a)
	}
}

func TestResolveRun(t *testing.T) {
	program := testProgram(t, "tool.exe")
	req, err := resolveRun("", []string{program, "a"})
	if err != nil {
		t.Fatal(err)
	}
	wd, _ := os.Getwd()
	if req.Program != program || req.Dir != wd || len(req.Args) != 1 || req.Args[0] != "a" {
		t.Fatalf("req = %+v", req)
	}
	if _, err := resolveRun("", []string{"titan-no-such-program"}); err == nil {
		t.Fatal("resolved a program that does not exist")
	}
	req, err = resolveRun(".", []string{program})
	if err != nil || !filepath.IsAbs(req.Dir) {
		t.Fatalf("req = %+v, %v; want an absolute dir", req, err)
	}
}
