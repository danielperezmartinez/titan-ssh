package main

import "errors"

// openScreen is never reached on Windows: runScreen hands the session to the
// desktop helper first (relayScreen), because session 0 cannot capture.
func openScreen() (*screenSource, error) {
	return nil, withCode(codeScreenCapture, errors.New("the remote desktop is served by the desktop helper"))
}

// directInput is false on Windows: the mouse pad goes through the desktop
// helper.
func directInput(string) (bool, error) { return false, nil }
