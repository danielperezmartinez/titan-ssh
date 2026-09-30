package procmem

import (
	"os"
	"testing"
	"time"
)

func TestParseStatHandlesSpacesAndParensInTheName(t *testing.T) {
	line := "4242 (my (odd) prog) S 17 4242 4242 0 -1 4194560 100 0 0 0 150 25 0 0 20 0 1 0 987654 1000 200 18446744073709551615"
	p, ok := parseStat(line)
	if !ok || p.ppid != 17 || p.start != 987654 || p.name != "my (odd) prog" {
		t.Fatalf("parseStat = %+v, %v", p, ok)
	}
	if p.cpu != 1750*time.Millisecond {
		t.Fatalf("cpu = %v; want 1.75s (175 ticks)", p.cpu)
	}
	if _, ok := parseStat("garbage"); ok {
		t.Fatal("a line without the name field must be rejected")
	}
}

func TestCwdReadsTheWorkingDirectory(t *testing.T) {
	want, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	if got := Cwd(os.Getpid()); got != want {
		t.Fatalf("Cwd = %q; want %q", got, want)
	}
	if got := Cwd(-1); got != "" {
		t.Fatalf("Cwd of a missing pid = %q", got)
	}
}
