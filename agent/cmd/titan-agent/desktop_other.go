//go:build !windows

package main

import "errors"

// Outside Windows there is no desktop helper yet: X11 and Wayland get their
// own injectors in their tasks (ADR-0016 §5).
const desktopSupported = false

func desktopSession() int { return 0 }

func launchDesktop(string) error {
	return withCode(codeInputUnsupported, errors.New("the mouse pad only supports Windows destinations for now"))
}

func desktopTaskExists(string) bool  { return false }
func deleteDesktopTask(string) error { return nil }
