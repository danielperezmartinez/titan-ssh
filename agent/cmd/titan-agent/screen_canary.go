//go:build canary

package main

// screenServed: this agent is the canary's (built with -tags canary), which
// serves the remote desktop while it is compared with the embedded RDP client
// (ADR-0029). The stable agent leaves it out entirely.
const screenServed = true
