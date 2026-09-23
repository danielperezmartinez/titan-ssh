// Package buffer holds the agent's per-session output ring buffer (ADR-0008).
package buffer

import "sync"

// Ring is a byte ring buffer that remembers the last Cap bytes of a session's
// PTY output while tracking absolute byte offsets. Head is the total number of
// bytes ever produced; Tail is the oldest offset still retained (Head - size).
//
// It is safe for concurrent use: the PTY reader appends while clients read for
// replay.
type Ring struct {
	mu   sync.Mutex
	data []byte // len == retained bytes, <= cap
	cap  int
	head uint64 // total bytes ever written
}

// New returns a ring that retains at most capBytes of history.
func New(capBytes int) *Ring {
	if capBytes < 1 {
		capBytes = 1
	}
	return &Ring{cap: capBytes}
}

// Append records p as freshly produced output and advances Head.
func (r *Ring) Append(p []byte) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.head += uint64(len(p))
	r.data = append(r.data, p...)
	if len(r.data) > r.cap {
		r.data = append([]byte(nil), r.data[len(r.data)-r.cap:]...)
	}
}

// Head returns the total number of bytes produced so far.
func (r *Ring) Head() uint64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.head
}

// Tail returns the oldest offset still retained.
func (r *Ring) Tail() uint64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.head - uint64(len(r.data))
}

// Since returns the retained bytes from absolute offset off onward, plus the
// effective offset they start at. If off is older than Tail, the returned
// offset is Tail (the caller must reset its view); if off is at or beyond Head,
// it returns an empty slice at Head.
func (r *Ring) Since(off uint64) (from uint64, out []byte) {
	r.mu.Lock()
	defer r.mu.Unlock()
	tail := r.head - uint64(len(r.data))
	switch {
	case off >= r.head:
		return r.head, nil
	case off < tail:
		return tail, append([]byte(nil), r.data...)
	default:
		start := int(off - tail)
		return off, append([]byte(nil), r.data[start:]...)
	}
}
