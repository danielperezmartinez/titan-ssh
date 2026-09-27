//go:build !aix && !(solaris && !illumos)

// Not on AIX and Solaris: their fcntl locks belong to the process, so a second
// lock from the same test process would not conflict.

package main

import (
	"errors"
	"testing"
)

func TestLockDaemonIsExclusive(t *testing.T) {
	dir := testStateDir(t)
	first, err := lockDaemon(dir)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := lockDaemon(dir); !errors.Is(err, errLocked) {
		t.Fatalf("a second lock must report errLocked, got %v", err)
	}
	if err := first.Close(); err != nil {
		t.Fatal(err)
	}
	again, err := lockDaemon(dir)
	if err != nil {
		t.Fatalf("the lock must be free once its holder closes it: %v", err)
	}
	again.Close()
}

func TestLockHeldReleasesItsProbe(t *testing.T) {
	dir := testStateDir(t)
	for range 2 { // the probe must not keep the lock
		if held, err := lockHeld(dir); err != nil || held {
			t.Fatalf("free lock reported held=%v err=%v", held, err)
		}
	}
	l, err := lockDaemon(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	if held, err := lockHeld(dir); err != nil || !held {
		t.Fatalf("taken lock reported held=%v err=%v", held, err)
	}
}
