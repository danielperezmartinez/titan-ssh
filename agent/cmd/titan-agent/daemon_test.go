package main

import (
	"errors"
	"io"
	"net"
	"testing"
	"time"

	"github.com/danielperezmartinez/titan-ssh/agent/internal/protocol"
	"github.com/danielperezmartinez/titan-ssh/agent/internal/session"
)

// idlePty is a PTY that produces no output until closed.
type idlePty struct{ closed chan struct{} }

func newIdlePty(cols, rows uint16) (session.Pty, error) {
	return &idlePty{closed: make(chan struct{})}, nil
}
func (p *idlePty) Read(b []byte) (int, error)  { <-p.closed; return 0, io.EOF }
func (p *idlePty) Write(b []byte) (int, error) { return len(b), nil }
func (p *idlePty) Resize(c, r uint16) error    { return nil }
func (p *idlePty) Close() error {
	select {
	case <-p.closed:
	default:
		close(p.closed)
	}
	return nil
}

// startServe runs serveConn on the server end of a pipe and returns the client
// end plus a channel closed when serveConn returns. serveConn waits for its
// reader goroutine, so returning means nothing was left behind.
func startServe(t *testing.T) (net.Conn, <-chan struct{}) {
	t.Helper()
	server, client := net.Pipe()
	reg := session.NewRegistry(newIdlePty, 1<<16)
	t.Cleanup(func() { reg.CloseAll() })
	done := make(chan struct{})
	go func() { serveConn(server, reg); close(done) }()
	return client, done
}

func waitReturned(t *testing.T, done <-chan struct{}) {
	t.Helper()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("serveConn did not return")
	}
}

func TestServeConnRefusesWithReasonWhenThePtyFails(t *testing.T) {
	cases := []struct {
		name    string
		factory session.PtyFactory
		want    string
	}{
		{"coded", func(c, r uint16) (session.Pty, error) {
			return nil, &session.PtyError{Code: session.CodeNoConPty, Err: errors.New("ConPTY needs\nWindows 10 1809")}
		}, "E_NO_CONPTY ConPTY needs Windows 10 1809"},
		{"uncoded", func(c, r uint16) (session.Pty, error) {
			return nil, errors.New("no shell")
		}, "E_PTY no shell"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			server, client := net.Pipe()
			defer client.Close()
			done := make(chan struct{})
			go func() { serveConn(server, session.NewRegistry(tc.factory, 1<<16)); close(done) }()

			hello := protocol.Frame{Type: protocol.TypeHello, SessionID: "s", Cols: 80, Rows: 24}
			if _, err := client.Write(protocol.Encode(hello)); err != nil {
				t.Fatal(err)
			}
			_ = client.SetReadDeadline(time.Now().Add(2 * time.Second))
			wire, _ := io.ReadAll(client) // serveConn closes after the BYE
			frames, err := (&protocol.Decoder{}).Feed(wire)
			if err != nil || len(frames) != 1 || frames[0].Type != protocol.TypeBye {
				t.Fatalf("got %+v (%v), want one BYE", frames, err)
			}
			if frames[0].Reason != tc.want {
				t.Errorf("reason = %q, want %q", frames[0].Reason, tc.want)
			}
			waitReturned(t, done)
		})
	}
}

func TestServeConnReturnsWhenClientClosesBeforeHello(t *testing.T) {
	client, done := startServe(t)
	client.Close()
	waitReturned(t, done)
}

func TestServeConnReturnsWhenClientStaysSilent(t *testing.T) {
	old := helloTimeout
	helloTimeout = 50 * time.Millisecond
	t.Cleanup(func() { helloTimeout = old })

	client, done := startServe(t)
	defer client.Close()
	waitReturned(t, done)
}

func TestServeConnRejectsFramesBeforeHello(t *testing.T) {
	client, done := startServe(t)
	defer client.Close()
	// More non-HELLO frames than the frame buffer holds, before any HELLO.
	go func() {
		input := protocol.Encode(protocol.Frame{Type: protocol.TypeInput, Bytes: []byte("x")})
		for i := 0; i < 200; i++ {
			if _, err := client.Write(input); err != nil {
				return
			}
		}
	}()
	waitReturned(t, done)
}

func TestServeConnReturnsWhenHandleEndsWhileClientKeepsSending(t *testing.T) {
	client, done := startServe(t)
	defer client.Close()
	go func() { _, _ = io.Copy(io.Discard, client) }()

	hello := protocol.Frame{Type: protocol.TypeHello, SessionID: "s", Cols: 80, Rows: 24}
	if _, err := client.Write(protocol.Encode(hello)); err != nil {
		t.Fatal(err)
	}
	if _, err := client.Write(protocol.Encode(protocol.Frame{Type: protocol.TypeBye})); err != nil {
		t.Fatal(err)
	}
	// Keep flooding past the frame buffer after Handle stopped consuming; the
	// reader must still exit instead of blocking on a send nobody receives.
	go func() {
		input := protocol.Encode(protocol.Frame{Type: protocol.TypeInput, Bytes: []byte("x")})
		for {
			if _, err := client.Write(input); err != nil {
				return
			}
		}
	}()
	waitReturned(t, done)
}
