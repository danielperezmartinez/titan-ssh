package procmem

import "testing"

func TestParseStatHandlesSpacesAndParensInTheName(t *testing.T) {
	line := "4242 (my (odd) prog) S 17 4242 4242 0 -1 4194560 100 0 0 0 1 2 0 0 20 0 1 0 987654 1000 200 18446744073709551615"
	ppid, start, ok := parseStat(line)
	if !ok || ppid != 17 || start != 987654 {
		t.Fatalf("parseStat = %d, %d, %v", ppid, start, ok)
	}
	if _, _, ok := parseStat("garbage"); ok {
		t.Fatal("a line without the name field must be rejected")
	}
}
