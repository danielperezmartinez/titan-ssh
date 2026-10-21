//go:build linux

package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestRemoteDesktopJSONContract pins the remoteDesktop part of --status
// --json that AgentRemoteDesktopTest reads on the app side.
func TestRemoteDesktopJSONContract(t *testing.T) {
	r := remoteDesktopReport{Desktop: "KDE (Wayland)", Consent: consentDialog, Remembered: true, Entry: true, Unattended: true, UnattendedAvailable: true}
	data, err := json.Marshal(r)
	if err != nil {
		t.Fatal(err)
	}
	want := `{"desktop":"KDE (Wayland)","consent":"dialog","remembered":true,"entry":true,"unattended":true,"unattendedAvailable":true}`
	if string(data) != want {
		t.Fatalf("got  %s\nwant %s", data, want)
	}
	data, _ = json.Marshal(remoteDesktopReport{Desktop: "GNOME (X11)", Consent: consentNone})
	if string(data) != `{"desktop":"GNOME (X11)","consent":"none"}` {
		t.Fatalf("X11: %s", data)
	}
}

func TestDesktopEntry(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XDG_DATA_HOME", dir)
	old := channel
	channel = "canary"
	defer func() { channel = old }()

	path := filepath.Join(dir, "applications", "io.github.danielperezmartinez.titanssh.canary.desktop")
	ensureDesktopEntry()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"[Desktop Entry]", "Name=titan-ssh canary (agent)", "NoDisplay=true", desktopEntryMarker, " --status"} {
		if !strings.Contains(string(data), want) {
			t.Errorf("entry lacks %q:\n%s", want, data)
		}
	}
	if _, ok := ownDesktopEntry(); !ok {
		t.Fatal("own entry not recognized")
	}
	if err := removeDesktopEntry(); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatal("entry still there")
	}

	// An entry the app installed under the id is never touched.
	_ = os.WriteFile(path, []byte("[Desktop Entry]\nName=titan-ssh\n"), 0o644)
	ensureDesktopEntry()
	if err := removeDesktopEntry(); err != nil {
		t.Fatal(err)
	}
	if data, _ := os.ReadFile(path); string(data) != "[Desktop Entry]\nName=titan-ssh\n" {
		t.Fatalf("the app's entry changed: %q", data)
	}

	if got := desktopExecQuote(`/home/a b/titan-agent`); got != `"/home/a b/titan-agent"` {
		t.Errorf("quote = %s", got)
	}
	if got := desktopExecQuote(`/opt/x$y`); got != `"/opt/x\\$y"` {
		t.Errorf("quote = %s", got)
	}
}
