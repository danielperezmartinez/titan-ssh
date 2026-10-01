//go:build !windows

package inject

// New has no backend outside Windows yet: X11 and Wayland come with their own
// tasks (ADR-0016 §5).
func New() (Injector, error) { return nil, ErrUnsupported }
