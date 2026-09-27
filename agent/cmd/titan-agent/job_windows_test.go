package main

import (
	"bytes"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

// agentJobEnv makes a helper process (see TestMain) enter a fresh Job Object
// with these limit flags before it runs main(), as Win32-OpenSSH does with
// each session's processes. The job's only handle is the helper's, so the
// job closes, and with KILL_ON_JOB_CLOSE kills what is left in it, exactly
// when the helper exits — like sshd when the session ends.
const agentJobEnv = "TITAN_AGENT_TEST_JOB_LIMITS"

func init() {
	if os.Getenv(agentMainEnv) != "1" {
		return
	}
	v := os.Getenv(agentJobEnv)
	if v == "" {
		return
	}
	limits, err := strconv.ParseUint(v, 0, 32)
	if err != nil {
		panic(err)
	}
	job, err := windows.CreateJobObject(nil, nil)
	if err != nil {
		panic(err)
	}
	var info windows.JOBOBJECT_EXTENDED_LIMIT_INFORMATION
	info.BasicLimitInformation.LimitFlags = uint32(limits)
	if _, err := windows.SetInformationJobObject(job, windows.JobObjectExtendedLimitInformation,
		uintptr(unsafe.Pointer(&info)), uint32(unsafe.Sizeof(info))); err != nil {
		panic(err)
	}
	if err := windows.AssignProcessToJobObject(job, windows.CurrentProcess()); err != nil {
		panic(err)
	}
	// The handle stays open for the helper's lifetime on purpose.
}

func TestLaunchFlags(t *testing.T) {
	const (
		killOnClose    = windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
		breakawayOK    = windows.JOB_OBJECT_LIMIT_BREAKAWAY_OK
		silentBreakway = windows.JOB_OBJECT_LIMIT_SILENT_BREAKAWAY_OK
	)
	tests := []struct {
		name      string
		inJob     bool
		limits    uint32
		wantFlags uint32
		wantCode  string
	}{
		{name: "no job", inJob: false, wantFlags: detachFlags},
		{name: "Win32-OpenSSH session job (0x2800)", inJob: true, limits: killOnClose | breakawayOK, wantFlags: detachFlags | windows.CREATE_BREAKAWAY_FROM_JOB},
		{name: "breakaway allowed, no kill on close", inJob: true, limits: breakawayOK, wantFlags: detachFlags | windows.CREATE_BREAKAWAY_FROM_JOB},
		{name: "silent breakaway", inJob: true, limits: killOnClose | silentBreakway, wantFlags: detachFlags},
		{name: "job without limits", inJob: true, limits: 0, wantFlags: detachFlags},
		{name: "kills on close, no way out", inJob: true, limits: killOnClose, wantCode: codeJobNoBreakaway},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := launchFlags(tt.inJob, tt.limits)
			if tt.wantCode != "" {
				assertCode(t, err, tt.wantCode)
				return
			}
			if err != nil || got != tt.wantFlags {
				t.Fatalf("got %#x, %v; want %#x", got, err, tt.wantFlags)
			}
		})
	}
}

// The mechanism of ADR-0009 §5 with a real job: the front runs in a job that
// kills on close but allows breakaway, launches the daemon and exits, which
// closes the job. The daemon and its session must survive.
func TestDaemonSurvivesTheFrontsJob(t *testing.T) {
	dir := testStateDir(t)
	t.Cleanup(func() { stopAgent(t, dir) })

	f := startFrontInJob(t, dir, windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE|windows.JOB_OBJECT_LIMIT_BREAKAWAY_OK)
	if ok := hello(t, f.stdin, f.out, "job-session"); !ok.Created {
		t.Fatal("the first attach should create the session")
	}
	f.close(t) // the job closes with the front and kills what is left in it

	time.Sleep(500 * time.Millisecond)
	st, err := readState(dir)
	if err != nil {
		t.Fatal(err)
	}
	conn, err := dialDaemon(st)
	if err != nil {
		t.Fatalf("the daemon must outlive the front's job: %v", err)
	}
	defer conn.Close()
	if ok := helloConn(t, conn, "job-session"); ok.Created {
		t.Fatal("the session must outlive the front's job too")
	}
}

// A job that kills on close and allows no breakaway: the front reports
// E_JOB_NO_BREAKAWAY and launches nothing.
func TestFrontReportsJobWithoutBreakaway(t *testing.T) {
	dir := testStateDir(t)
	cmd := agentCmd(t, "--state-dir", dir)
	cmd.Env = append(cmd.Env, agentJobEnv+"="+strconv.Itoa(windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE))
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Run(); err == nil {
		t.Fatal("the front must fail")
	}
	if !strings.HasPrefix(stderr.String(), "TITAN_AGENT_ERROR "+codeJobNoBreakaway+" ") {
		t.Fatalf("want an %s line on stderr, got %q", codeJobNoBreakaway, stderr.String())
	}
	if held, err := lockHeld(dir); err != nil || held {
		t.Fatalf("no daemon may start: held=%v err=%v", held, err)
	}
}

func startFrontInJob(t *testing.T, dir string, limits uint32) *front {
	t.Helper()
	cmd := agentCmd(t, "--state-dir", dir)
	cmd.Env = append(cmd.Env, agentJobEnv+"="+strconv.FormatUint(uint64(limits), 10))
	return startFrontCmd(t, cmd)
}
