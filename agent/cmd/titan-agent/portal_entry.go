//go:build linux

package main

import (
	"os"
	"path/filepath"
	"strings"
)

// The portal's Registry only takes an app id with a desktop entry behind it
// (ADR-0031 §3). The agent writes a hidden one for its app id the first time
// it serves the remote desktop, so the desktop's dialog and its permission
// lists name titan-ssh and the unattended access can be granted to this
// agent alone. --status shows it and --desktop-entry remove takes it away.

// applicationsDir is where the user's desktop entries go.
func applicationsDir() (string, error) {
	if dir := os.Getenv("XDG_DATA_HOME"); filepath.IsAbs(dir) {
		return filepath.Join(dir, "applications"), nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, ".local", "share", "applications"), nil
}

// desktopEntryPath is the agent's desktop entry file.
func desktopEntryPath() (string, error) {
	dir, err := applicationsDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, portalAppID()+".desktop"), nil
}

// desktopEntryMarker tells the agent's own entry from one the app installed
// under the same id, which it never touches.
const desktopEntryMarker = "X-Titan-Agent=true"

// desktopEntry is the entry's text.
func desktopEntry() string {
	name := "titan-ssh"
	if channel == "canary" {
		name = "titan-ssh canary"
	}
	exe, err := os.Executable()
	if err != nil {
		exe = "titan-agent"
	}
	return strings.Join([]string{
		"[Desktop Entry]",
		"Type=Application",
		"Name=" + name + " (agent)",
		"Name[es]=" + name + " (agente)",
		"Comment=Remote desktop of " + name + " through its agent",
		"Comment[es]=Escritorio remoto de " + name + " a través de su agente",
		"Exec=" + desktopExecQuote(exe) + " --status",
		"Icon=preferences-desktop-remote-desktop",
		"NoDisplay=true",
		"Terminal=false",
		desktopEntryMarker,
		"",
	}, "\n")
}

// desktopExecQuote quotes a path for an Exec line when it needs it.
func desktopExecQuote(s string) string {
	if !strings.ContainsAny(s, " \t\"'\\$`") {
		return s
	}
	r := strings.NewReplacer(`\`, `\\\\`, `"`, `\\"`, "$", `\\$`, "`", "\\\\`")
	return `"` + r.Replace(s) + `"`
}

// ensureDesktopEntry writes the agent's desktop entry if there is none under
// its app id. An entry the app installed stays as it is. A failure only means
// the portal does not take the app id.
func ensureDesktopEntry() {
	path, err := desktopEntryPath()
	if err != nil {
		return
	}
	if _, err := os.Stat(path); err == nil {
		return
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), ".titan-entry-*")
	if err != nil {
		return
	}
	defer os.Remove(tmp.Name())
	_, err = tmp.WriteString(desktopEntry())
	if cerr := tmp.Close(); err != nil || cerr != nil {
		return
	}
	_ = os.Chmod(tmp.Name(), 0o644)
	_ = os.Rename(tmp.Name(), path)
}

// ownDesktopEntry tells whether the agent's own entry is installed.
func ownDesktopEntry() (string, bool) {
	path, err := desktopEntryPath()
	if err != nil {
		return "", false
	}
	data, err := os.ReadFile(path)
	if err != nil || !strings.Contains(string(data), desktopEntryMarker) {
		return path, false
	}
	return path, true
}

// removeDesktopEntry deletes the agent's own entry (not one the app
// installed).
func removeDesktopEntry() error {
	path, ok := ownDesktopEntry()
	if !ok {
		return nil
	}
	return os.Remove(path)
}
