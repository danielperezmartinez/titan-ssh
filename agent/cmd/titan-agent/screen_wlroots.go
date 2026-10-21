//go:build linux

package main

import "github.com/danielperezmartinez/titan-ssh/agent/internal/graphical"

// openWlrootsScreen serves a Wayland desktop through the wlroots protocols
// (image copy capture, virtual pointer and keyboard; ADR-0031 §2). Not
// written yet: task "Escritorio remoto en destinos Linux wlroots".
func openWlrootsScreen(graphical.Session) (*screenSource, error) { return nil, errNoWay }
