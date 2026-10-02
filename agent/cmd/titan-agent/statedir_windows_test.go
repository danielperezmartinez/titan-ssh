package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"golang.org/x/sys/windows"
)

func TestDefaultStateDirUsesLocalAppData(t *testing.T) {
	t.Setenv("LOCALAPPDATA", `C:\Profile\AppData\Local`)
	got, err := defaultStateDir()
	if err != nil {
		t.Fatal(err)
	}
	if want := `C:\Profile\AppData\Local\titan-ssh`; got != want {
		t.Fatalf("got %q, want %q", got, want)
	}
}

func TestEnsureStateDirCreatesDir(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "a", "titan")
	if err := ensureStateDir(dir); err != nil {
		t.Fatalf("fresh dir should be accepted: %v", err)
	}
	if fi, err := os.Stat(dir); err != nil || !fi.IsDir() {
		t.Fatalf("dir should exist: %v", err)
	}
}

func TestEnsureStateDirRejectsFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "file")
	if err := os.WriteFile(path, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := ensureStateDir(path); err == nil {
		t.Fatal("a file in place of the state dir must be rejected")
	}
}

func TestEnsureStateDirRejectsJunction(t *testing.T) {
	base := t.TempDir()
	target := filepath.Join(base, "real")
	if err := os.Mkdir(target, 0o700); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(base, "link")
	// Junctions need no privilege, unlike symlinks.
	if out, err := exec.Command("cmd", "/c", "mklink", "/J", link, target).CombinedOutput(); err != nil {
		t.Skipf("cannot create a junction: %v: %s", err, out)
	}
	if err := ensureStateDir(link); err == nil {
		t.Fatal("a junction in place of the state dir must be rejected")
	}
}

// icacls runs the system's icacls on path.
func icacls(t *testing.T, path string, args ...string) string {
	t.Helper()
	out, err := exec.Command("icacls", append([]string{path}, args...)...).CombinedOutput()
	if err != nil {
		t.Fatalf("icacls %s %v: %v: %s", path, args, err, out)
	}
	return string(out)
}

func TestEnsureStateDirCreatesItPrivate(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "titan")
	if err := ensureStateDir(dir); err != nil {
		t.Fatal(err)
	}
	sd, err := windows.GetNamedSecurityInfo(dir, windows.SE_FILE_OBJECT,
		windows.DACL_SECURITY_INFORMATION|windows.LABEL_SECURITY_INFORMATION)
	if err != nil {
		t.Fatal(err)
	}
	user, err := currentUserSID()
	if err != nil {
		t.Fatal(err)
	}
	want := "D:P(A;OICI;FA;;;" + user.String() + ")(A;OICI;FA;;;SY)"
	if got := sd.String(); !strings.HasPrefix(got, want) || !strings.Contains(got, "S:AI(ML;OICI;NWNR;;;ME)") {
		t.Fatalf("security of a new state dir = %s, want %s and the no-read-up label", got, want)
	}
}

func TestPrivateTempFilesHaveTheirOwnACL(t *testing.T) {
	// A dir that passes on access for Everyone: the file must not inherit it.
	dir := t.TempDir()
	icacls(t, dir, "/grant", "*S-1-1-0:(OI)(CI)R")
	f, err := createPrivateTemp(dir, ".agent-*.json")
	if err != nil {
		t.Fatal(err)
	}
	name := f.Name()
	if _, err := f.Write([]byte("{}")); err != nil {
		t.Fatal(err)
	}
	f.Close()
	if !strings.HasPrefix(filepath.Base(name), ".agent-") || !strings.HasSuffix(name, ".json") {
		t.Fatalf("name %s does not follow the pattern", name)
	}
	moved := filepath.Join(dir, "agent.json")
	if err := os.Rename(name, moved); err != nil {
		t.Fatal(err)
	}
	sd, err := windows.GetNamedSecurityInfo(moved, windows.SE_FILE_OBJECT, windows.DACL_SECURITY_INFORMATION)
	if err != nil {
		t.Fatal(err)
	}
	user, err := currentUserSID()
	if err != nil {
		t.Fatal(err)
	}
	if got, want := sd.String(), "D:P(A;;FA;;;"+user.String()+")(A;;FA;;;SY)"; got != want {
		t.Fatalf("file DACL = %s, want %s", got, want)
	}
}

func TestEnsureStateDirRejectsADirOthersCanReach(t *testing.T) {
	for _, grant := range []string{"*S-1-1-0:R", "*S-1-5-11:(OI)(CI)M", "*S-1-5-32-545:(CI)W"} {
		dir := filepath.Join(t.TempDir(), "titan")
		if err := os.Mkdir(dir, 0o700); err != nil {
			t.Fatal(err)
		}
		icacls(t, dir, "/inheritance:r", "/grant", "*S-1-5-18:(OI)(CI)F", "/grant", "*S-1-5-32-544:(OI)(CI)F")
		user, err := currentUserSID()
		if err != nil {
			t.Fatal(err)
		}
		icacls(t, dir, "/grant", "*"+user.String()+":(OI)(CI)F")
		if err := ensureStateDir(dir); err != nil {
			t.Fatalf("a dir for the user, SYSTEM and Administrators only must pass: %v", err)
		}
		icacls(t, dir, "/grant", grant)
		if err := ensureStateDir(dir); err == nil {
			t.Errorf("a state dir with %s must be rejected", grant)
		}
	}
}

func TestEnsureStateDirRejectsANullDACL(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "titan")
	if err := os.Mkdir(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := windows.SetNamedSecurityInfo(dir, windows.SE_FILE_OBJECT, windows.DACL_SECURITY_INFORMATION, nil, nil, nil, nil); err != nil {
		t.Fatal(err)
	}
	if err := ensureStateDir(dir); err == nil {
		t.Fatal("a state dir with no access list must be rejected")
	}
}
