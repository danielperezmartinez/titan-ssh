package main

import (
	"errors"
	"sync"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/graphical"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/inject"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/screen"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/x11"
)

// findDesktop finds the user's graphical session (ADR-0031 §1). Errors carry
// their contract code: none is E_NO_DESKTOP, one not on screen
// E_DESKTOP_INACTIVE.
func findDesktop() (graphical.Session, error) {
	s, err := graphical.Find()
	switch {
	case errors.Is(err, graphical.ErrInactive):
		return s, withCode(codeDesktopInactive, err)
	case err != nil:
		return s, withCode(codeNoDesktop, err)
	}
	return s, nil
}

// dialX11 connects to the X server of s.
func dialX11(s graphical.Session) (*x11.Conn, error) {
	d, err := x11.ParseDisplay(s.Display)
	if err != nil {
		return nil, withCode(codeNoDesktop, err)
	}
	x, err := x11.Dial(d, s.Xauthority)
	if err != nil {
		return nil, withCode(codeNoDesktop, err)
	}
	return x, nil
}

// openScreen serves the user's desktop the way its session allows: X11 here,
// Wayland in screen_wayland.go.
func openScreen() (*screenSource, error) {
	s, err := findDesktop()
	if err != nil {
		return nil, err
	}
	if s.Kind == graphical.Wayland {
		return openWaylandScreen(s)
	}
	return openX11Screen(s)
}

// openX11Screen captures the X11 session and injects into it, with one
// connection to the X server (ADR-0029 §5).
func openX11Screen(s graphical.Session) (*screenSource, error) {
	x, err := dialX11(s)
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
	s, err := findDesktop()
	if err != nil {
		return true, err
	}
	if s.Kind != graphical.X11 {
		// The wlroots family through its virtual pointer and keyboard, only
		// in the canary for now, like the remote desktop that brought it.
		if screenServed {
			ways := []func(graphical.Session) error{portalInputWay(stateDir), wlrootsInput}
			if s.Family() == graphical.FamilyWlroots {
				ways[0], ways[1] = ways[1], ways[0]
			}
			for _, way := range ways {
				if err := way(s); !errors.Is(err, errNoWay) {
					return true, err
				}
			}
		}
		return true, withCode(codeInputUnsupported, errors.New("the mouse pad does not support "+s.Describe()+" yet"))
	}
	x, err := dialX11(s)
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
