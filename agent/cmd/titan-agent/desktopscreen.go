package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/inject"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/screen"
)

// On Windows a remote desktop session (--screen) cannot capture from where
// sshd runs it, session 0, any more than the mouse pad can inject from there.
// The front reaches the desktop helper with a desktopScreenMagic connection
// and the helper serves the session (ADR-0031 §4). Before the screen
// protocol, the helper answers with one desktopReply line: empty when it has
// the screen, or the error and its contract code, which the front reports
// as its own. The helper sends nothing else until the client's SCREEN_HELLO,
// so the front can read that line and then splice.

// openHelperScreen opens the capturer the helper serves a session with, and
// what to close at the end. A var so tests can serve a fake screen.
var openHelperScreen = openDesktopScreen

// serveScreen serves one remote desktop session on conn, with the helper's
// injector for the input.
func (h *desktopHelper) serveScreen(conn net.Conn) {
	capturer, closeScreen, err := openHelperScreen()
	if err != nil {
		reply := desktopReply{Error: err.Error(), Code: codeScreenCapture}
		var ae *agentError
		if errors.As(err, &ae) {
			reply.Error, reply.Code = ae.Err.Error(), ae.Code
		}
		writeDesktopReply(conn, reply)
		return
	}
	defer closeScreen()
	writeDesktopReply(conn, desktopReply{})
	_ = screen.Serve(conn, capturer, inject.NewHandler(h.inj, &h.injMu), screen.Options{})
}

// relayScreen serves --screen through the desktop helper, launching it first
// if needed, on systems that have one. It reports false where the front
// captures by itself.
func relayScreen(stateDir string) (bool, error) {
	if !desktopSupported {
		return false, nil
	}
	conn, err := connectDesktopWith(stateDir, desktopScreenMagic, func() error { return launchDesktop(stateDir) })
	if err != nil {
		return true, err
	}
	defer conn.Close()
	if err := screenReady(conn); err != nil {
		return true, err
	}
	splice(conn)
	return true, nil
}

// screenReady reads the helper's answer to a screen connection. It reads
// byte by byte, so nothing past the line is taken from the splice.
func screenReady(conn net.Conn) error {
	_ = conn.SetReadDeadline(time.Now().Add(controlTimeout))
	var line []byte
	b := make([]byte, 1)
	for {
		if _, err := conn.Read(b); err != nil {
			return withCode(codeScreenCapture, fmt.Errorf("no answer from the desktop helper: %w", err))
		}
		if b[0] == '\n' {
			break
		}
		if len(line) >= controlLineMax {
			return withCode(codeScreenCapture, errors.New("the desktop helper's answer is too long"))
		}
		line = append(line, b[0])
	}
	_ = conn.SetReadDeadline(time.Time{})
	var reply desktopReply
	if err := json.Unmarshal(line, &reply); err != nil {
		return withCode(codeScreenCapture, fmt.Errorf("unreadable answer from the desktop helper: %w", err))
	}
	if reply.Error != "" {
		code := reply.Code
		if code == "" {
			code = codeScreenCapture
		}
		return withCode(code, errors.New(reply.Error))
	}
	return nil
}
