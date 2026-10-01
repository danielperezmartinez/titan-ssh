package main

import (
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"
)

// Names inside the per-user state dir (ADR-0009 §2-3).
const (
	stateDirName  = "titan-ssh"  // under the platform's state / app-data root
	lockFileName  = "agent.lock" // single-instance lock, held by the live daemon
	stateFileName = "agent.json" // rendezvous record, written by the lock holder
)

const (
	stateSchema = 1
	tokenLen    = 32 // bytes of crypto/rand; hex-encoded in the state file
)

// agentState is the daemon's rendezvous record: where it listens and the
// secret a front must present. Only the daemon holding the lock writes it,
// and only once it is listening.
type agentState struct {
	Schema int    `json:"schema"`
	Agent  string `json:"agent"` // version of the daemon that wrote it
	Port   int    `json:"port"`  // on 127.0.0.1
	Token  string `json:"token"` // hex of the preamble secret
	PID    int    `json:"pid"`
	// Session is the Windows session the desktop helper runs in (desktop.json
	// only).
	Session int `json:"session,omitempty"`
}

// token decodes the preamble secret.
func (s agentState) token() ([]byte, error) {
	t, err := hex.DecodeString(s.Token)
	if err != nil || len(t) != tokenLen {
		return nil, errors.New("state file has a malformed token")
	}
	return t, nil
}

// writeState publishes st atomically: a private temp file in the same dir
// (created O_EXCL, 0600), synced, then renamed over agent.json. A reader sees
// the old record or the new one, never a torn one.
func writeState(dir string, st agentState) error {
	return writeStateFile(dir, stateFileName, st)
}

// writeStateFile is writeState for the record called name (agent.json, or
// the desktop helper's desktop.json).
func writeStateFile(dir, name string, st agentState) error {
	data, err := json.Marshal(st)
	if err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, ".agent-*.json")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name()) // no-op once renamed
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return renameRetrying(tmp.Name(), filepath.Join(dir, name))
}

// renameRetrying renames, retrying briefly: on Windows the rename fails while a
// front happens to have agent.json open for reading.
func renameRetrying(from, to string) error {
	var err error
	for range 50 {
		if err = os.Rename(from, to); err == nil {
			return nil
		}
		time.Sleep(20 * time.Millisecond)
	}
	return err
}

// readState loads and validates agent.json. A missing file is reported as
// fs.ErrNotExist (no daemon has published yet).
func readState(dir string) (agentState, error) {
	return readStateFile(dir, stateFileName)
}

// readStateFile is readState for the record called name.
func readStateFile(dir, name string) (agentState, error) {
	var st agentState
	data, err := os.ReadFile(filepath.Join(dir, name))
	if err != nil {
		return st, err
	}
	if err := json.Unmarshal(data, &st); err != nil {
		return st, fmt.Errorf("state file is unreadable: %w", err)
	}
	if st.Schema != stateSchema {
		return st, fmt.Errorf("state file has schema %d, want %d", st.Schema, stateSchema)
	}
	if st.Port < 1 || st.Port > 65535 {
		return st, fmt.Errorf("state file has an invalid port %d", st.Port)
	}
	if _, err := st.token(); err != nil {
		return st, err
	}
	return st, nil
}

// removeOwnState deletes agent.json if it is still the record of process pid,
// so a daemon never removes the record of the one that replaced it.
func removeOwnState(dir string, pid int) {
	removeOwnStateFile(dir, stateFileName, pid)
}

// removeOwnStateFile is removeOwnState for the record called name.
func removeOwnStateFile(dir, name string, pid int) {
	if st, err := readStateFile(dir, name); err == nil && st.PID == pid {
		_ = os.Remove(filepath.Join(dir, name))
	}
}
