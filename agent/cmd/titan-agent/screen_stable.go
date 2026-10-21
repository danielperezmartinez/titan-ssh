//go:build !canary

package main

// screenServed: the stable agent does not serve the remote desktop, which is
// still on trial in the canary (ADR-0029); with the constant false, the linker
// leaves the screen encoder out of the binary.
const screenServed = false
