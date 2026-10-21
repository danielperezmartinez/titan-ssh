//go:build !canary

package main

import (
	"errors"
	"testing"
)

// The stable agent does not serve the remote desktop on trial in the canary
// (ADR-0029): --screen answers E_SCREEN_UNSUPPORTED before touching anything.
func TestStableAgentDoesNotServeTheScreen(t *testing.T) {
	err := runScreen(t.TempDir())
	var ae *agentError
	if !errors.As(err, &ae) || ae.Code != codeScreenUnsupported {
		t.Fatalf("runScreen = %v, want %s", err, codeScreenUnsupported)
	}
}
