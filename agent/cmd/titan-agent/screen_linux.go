package main

import (
	"errors"
	"sync"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/inject"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/screen"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/x11"
)

// openX11 connects to the user's X11 session. Errors carry their contract
// code: no session is E_NO_DESKTOP, a Wayland session is unsupported (code).
func openX11(unsupported string) (*x11.Conn, error) {
	s, err := x11.FindSession()
	if errors.Is(err, x11.ErrNoSession) {
		return nil, withCode(codeNoDesktop, err)
	}
	if err != nil {
		return nil, withCode(codeNoDesktop, err)
	}
	if s.Wayland {
		return nil, withCode(unsupported, errors.New("the desktop runs Wayland, which is not supported yet"))
	}
	x, err := x11.Dial(s.Display, s.Xauthority)
	if err != nil {
		return nil, withCode(codeNoDesktop, err)
	}
	return x, nil
}

// openScreen captures the user's X11 session and injects into it, with one
// connection to the X server (ADR-0029 §5).
func openScreen() (*screenSource, error) {
	x, err := openX11(codeScreenUnsupported)
	if err != nil {
		return nil, err
	}
	inj, err := inject.NewX11(x)
	if err != nil {
		x.Close()
		return nil, withCode(codeNoDesktop, err)
	}
	var mu sync.Mutex
	return &screenSource{
		capturer: screen.NewX11(x),
		input:    inject.NewHandler(inj, &mu),
		close: func() {
			inj.Restore()
			x.Close()
		},
	}, nil
}

// directInput serves a mouse pad (--input) on X11 in this process: the SSH
// session can reach the user's X server itself, so there is no helper.
func directInput(stateDir string) (bool, error) {
	x, err := openX11(codeInputUnsupported)
	if err != nil {
		return true, err
	}
	defer x.Close()
	inj, err := inject.NewX11(x)
	if err != nil {
		return true, withCode(codeNoDesktop, err)
	}
	defer inj.Restore()
	var mu sync.Mutex
	return true, inject.Serve(stdio(), inj, &mu)
}
