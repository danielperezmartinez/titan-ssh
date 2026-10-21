//go:build !windows && !linux

package inject

// New has no backend outside Windows and Linux X11 yet (ADR-0016 §5,
// ADR-0029 §5).
func New() (Injector, error) { return nil, ErrUnsupported }
