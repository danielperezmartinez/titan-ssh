//go:build linux

package main

import (
	"errors"
	"fmt"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/graphical"
)

// waylandWay is one way to serve a Wayland desktop (ADR-0031 §2). It returns
// errNoWay when it does not apply to the session, so the next one is tried;
// any other error is the session's answer.
type waylandWay func(graphical.Session) (*screenSource, error)

// errNoWay says a waylandWay does not apply to this desktop.
var errNoWay = errors.New("this way does not apply to the desktop")

// waylandWays is the order to try the ways in: the portal first, except on
// the wlroots family, whose portal has no RemoteDesktop.
func waylandWays(s graphical.Session) []waylandWay {
	if s.Family() == graphical.FamilyWlroots {
		return []waylandWay{openWlrootsScreen, openPortalScreen}
	}
	return []waylandWay{openPortalScreen, openWlrootsScreen}
}

// openWaylandScreen serves a Wayland desktop through the first way that
// applies to it.
func openWaylandScreen(s graphical.Session) (*screenSource, error) {
	for _, way := range waylandWays(s) {
		src, err := way(s)
		if errors.Is(err, errNoWay) {
			continue
		}
		return src, err
	}
	return nil, withCode(codeScreenUnsupported, fmt.Errorf("%s is not supported yet", s.Describe()))
}
