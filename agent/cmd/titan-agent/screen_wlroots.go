//go:build linux

package main

import (
	"errors"
	"fmt"
	"sync"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/graphical"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/inject"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/screen"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/wayland"
)

// openWlrootsScreen serves a Wayland desktop through the wlroots protocols
// (ADR-0031 §2): ext-image-copy-capture or wlr-screencopy for the image, and
// the virtual pointer and keyboard for the input, all on one connection to
// the compositor. errNoWay when the compositor offers no capture protocol,
// so the portal is tried; without the virtual input the desktop is only
// shown.
func openWlrootsScreen(s graphical.Session) (*screenSource, error) {
	c, err := wayland.Dial(wayland.SocketPath(s.RuntimeDir, s.WaylandDisplay))
	if err != nil {
		if s.Family() != graphical.FamilyWlroots {
			return nil, errNoWay
		}
		return nil, withCode(codeScreenCapture, fmt.Errorf("%s: %w", s.Describe(), err))
	}
	inj, err := inject.NewWayland(c)
	if err != nil && !errors.Is(err, inject.ErrNoVirtualInput) {
		c.Close()
		return nil, withCode(codeScreenCapture, err)
	}
	var pointer uint32
	if inj != nil {
		pointer = inj.Pointer()
	}
	capt, err := screen.NewWlroots(c, pointer)
	if err != nil {
		c.Close()
		if errors.Is(err, screen.ErrNoWlrootsCapture) {
			return nil, errNoWay
		}
		return nil, withCode(codeScreenCapture, err)
	}
	src := &screenSource{capturer: capt, close: func() { c.Close() }}
	if inj != nil {
		inj.SetTarget(capt.PointerTarget)
		var mu sync.Mutex
		src.input = inject.NewHandler(inj, &mu)
	}
	return src, nil
}

// wlrootsInput serves a mouse pad (--input) on a wlroots compositor through
// the virtual pointer and keyboard; errNoWay if it has none.
func wlrootsInput(s graphical.Session) error {
	c, err := wayland.Dial(wayland.SocketPath(s.RuntimeDir, s.WaylandDisplay))
	if err != nil {
		if s.Family() != graphical.FamilyWlroots {
			return errNoWay
		}
		return withCode(codeNoDesktop, fmt.Errorf("%s: %w", s.Describe(), err))
	}
	defer c.Close()
	inj, err := inject.NewWayland(c)
	if errors.Is(err, inject.ErrNoVirtualInput) {
		return errNoWay
	}
	if err != nil {
		return withCode(codeNoDesktop, err)
	}
	var mu sync.Mutex
	return inject.Serve(stdio(), inj, &mu)
}
