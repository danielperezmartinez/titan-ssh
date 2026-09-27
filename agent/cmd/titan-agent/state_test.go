package main

import (
	"encoding/hex"
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// testStateDir is a fresh private state dir. t.TempDir itself is not one: on
// Unix it is group/other-readable, which ensureStateDir rightly rejects.
func testStateDir(t *testing.T) string {
	t.Helper()
	dir := filepath.Join(t.TempDir(), stateDirName)
	if err := ensureStateDir(dir); err != nil {
		t.Fatal(err)
	}
	return dir
}

func validState() agentState {
	return agentState{
		Schema: stateSchema,
		Agent:  "test",
		Port:   4242,
		Token:  strings.Repeat("ab", tokenLen),
		PID:    1234,
	}
}

func TestStateRoundTrips(t *testing.T) {
	dir := testStateDir(t)
	want := validState()
	if err := writeState(dir, want); err != nil {
		t.Fatal(err)
	}
	got, err := readState(dir)
	if err != nil {
		t.Fatal(err)
	}
	if got != want {
		t.Fatalf("got %+v, want %+v", got, want)
	}
	tok, err := got.token()
	if err != nil || hex.EncodeToString(tok) != want.Token {
		t.Fatalf("token decode: %x, %v", tok, err)
	}
}

func TestWriteStateReplacesAndLeavesNoTempFiles(t *testing.T) {
	dir := testStateDir(t)
	st := validState()
	if err := writeState(dir, st); err != nil {
		t.Fatal(err)
	}
	st.Port = 5353
	if err := writeState(dir, st); err != nil {
		t.Fatal(err)
	}
	got, err := readState(dir)
	if err != nil || got.Port != 5353 {
		t.Fatalf("second write should win: %+v, %v", got, err)
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 {
		t.Fatalf("only agent.json should remain, got %v", entries)
	}
}

func TestReadStateMissingIsNotExist(t *testing.T) {
	if _, err := readState(testStateDir(t)); !errors.Is(err, fs.ErrNotExist) {
		t.Fatalf("want fs.ErrNotExist, got %v", err)
	}
}

func TestReadStateRejectsInvalid(t *testing.T) {
	tests := []struct {
		name string
		body string
	}{
		{name: "not json", body: "{"},
		{name: "other schema", body: `{"schema":2,"port":4242,"token":"` + strings.Repeat("ab", tokenLen) + `","pid":1}`},
		{name: "no port", body: `{"schema":1,"port":0,"token":"` + strings.Repeat("ab", tokenLen) + `","pid":1}`},
		{name: "port too big", body: `{"schema":1,"port":70000,"token":"` + strings.Repeat("ab", tokenLen) + `","pid":1}`},
		{name: "short token", body: `{"schema":1,"port":4242,"token":"abab","pid":1}`},
		{name: "non-hex token", body: `{"schema":1,"port":4242,"token":"` + strings.Repeat("zz", tokenLen) + `","pid":1}`},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			dir := testStateDir(t)
			if err := os.WriteFile(filepath.Join(dir, stateFileName), []byte(tt.body), 0o600); err != nil {
				t.Fatal(err)
			}
			if _, err := readState(dir); err == nil {
				t.Fatal("invalid state file must be rejected")
			}
		})
	}
}

func TestRemoveOwnStateOnlyRemovesOwnRecord(t *testing.T) {
	dir := testStateDir(t)
	st := validState()
	if err := writeState(dir, st); err != nil {
		t.Fatal(err)
	}
	removeOwnState(dir, st.PID+1)
	if _, err := readState(dir); err != nil {
		t.Fatalf("another daemon's record must stay: %v", err)
	}
	removeOwnState(dir, st.PID)
	if _, err := readState(dir); !errors.Is(err, fs.ErrNotExist) {
		t.Fatalf("own record must go, got %v", err)
	}
}

func TestContractLineIsOneLine(t *testing.T) {
	err := withCode(codeLock, errors.New("flock:\n  no locks available"))
	var ae *agentError
	if !errors.As(err, &ae) {
		t.Fatal("withCode must return an *agentError")
	}
	if got, want := ae.contractLine(), "TITAN_AGENT_ERROR E_LOCK flock: no locks available"; got != want {
		t.Fatalf("got %q, want %q", got, want)
	}
}
