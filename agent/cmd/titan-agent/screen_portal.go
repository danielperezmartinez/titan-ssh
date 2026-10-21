//go:build linux

package main

import "github.com/danielperezmartinez/titan-ssh/agent/internal/graphical"

// openPortalScreen serves a Wayland desktop through the RemoteDesktop and
// ScreenCast portals and PipeWire (KDE, GNOME; ADR-0031 §2). Not written yet:
// task "Escritorio remoto en destinos Linux Wayland".
func openPortalScreen(graphical.Session) (*screenSource, error) { return nil, errNoWay }
