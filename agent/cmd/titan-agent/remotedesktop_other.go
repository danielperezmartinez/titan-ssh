//go:build !linux

package main

import "errors"

// remoteDesktopReport is the remote desktop part of --status --json; only
// Linux Wayland desktops ask a consent the agent can change (ADR-0031 §3).
type remoteDesktopReport struct {
	Desktop             string `json:"desktop"`
	Consent             string `json:"consent"`
	Remembered          bool   `json:"remembered,omitempty"`
	Entry               bool   `json:"entry,omitempty"`
	Unattended          bool   `json:"unattended,omitempty"`
	UnattendedAvailable bool   `json:"unattendedAvailable,omitempty"`
}

// screenUnattended is --screen --unattended; nothing to pre-authorize here.
var screenUnattended bool

func queryRemoteDesktop(string) *remoteDesktopReport { return nil }

func runRemoteDesktop(string, string) error {
	return withCode(codeScreenUnsupported, errors.New("this system asks no consent for the remote desktop that the agent can change"))
}
