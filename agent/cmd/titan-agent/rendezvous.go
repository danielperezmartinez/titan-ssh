package main

import (
	"crypto/subtle"
	"errors"
	"io"
	"net"
	"strconv"
	"time"
)

// The front↔daemon hop runs over TCP on 127.0.0.1 (ADR-0009 §2). Before any
// byte of the client protocol, the front sends a preamble (fixed magic + the
// raw token from the state file) and the daemon answers with a fixed ack. The
// token never leaves the host: the client↔agent protocol on the SSH channel is
// unchanged, and only the front, on the destination, knows the token.
const (
	preambleMagic = "TTNAGNT1"
	preambleAck   = "TTNAGOK1"
	// A control connection (control.go) has its own magic and ack: a daemon
	// that predates it closes the connection, which tells the caller it is
	// talking to an older agent.
	controlMagic = "TTNACTL1"
	controlAck   = "TTNACOK1"
	// The desktop helper (desktop_windows.go) has its own listener, token and
	// magics: an input connection from a --input front, and a control one.
	desktopMagic        = "TTNADSK1"
	desktopAck          = "TTNADOK1"
	desktopControlMagic = "TTNADCT1"
	desktopControlAck   = "TTNADCO1"
)

// dialTimeout bounds the connect plus the preamble exchange. A var so tests
// can shorten it.
var dialTimeout = 3 * time.Second

// errRejected reports that something listens on the published port but did not
// accept the token: a stale state file whose port now belongs to someone else,
// or a daemon holding another token.
var errRejected = errors.New("the daemon did not accept the token")

// dialDaemon connects to the daemon st describes and authenticates. Waiting for
// the ack means the client's bytes only ever reach a daemon that knows the
// token, never whatever took over the port of a stale state file.
//
// security: with a stale file, the preamble does reach whoever took the port,
// but that token is dead: its daemon is gone (it would still hold the port
// otherwise) and each new daemon draws a fresh one.
func dialDaemon(st agentState) (net.Conn, error) {
	return dialWith(st, preambleMagic, preambleAck)
}

// dialControl is dialDaemon for a control connection (control.go).
func dialControl(st agentState) (net.Conn, error) {
	return dialWith(st, controlMagic, controlAck)
}

func dialWith(st agentState, magic, wantAck string) (net.Conn, error) {
	token, err := st.token()
	if err != nil {
		return nil, err
	}
	conn, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(st.Port)), dialTimeout)
	if err != nil {
		return nil, err
	}
	_ = conn.SetDeadline(time.Now().Add(dialTimeout))
	pre := append([]byte(magic), token...)
	ack := make([]byte, len(wantAck))
	if _, err := conn.Write(pre); err != nil {
		conn.Close()
		return nil, errRejected
	}
	if _, err := io.ReadFull(conn, ack); err != nil || string(ack) != wantAck {
		conn.Close()
		return nil, errRejected
	}
	_ = conn.SetDeadline(time.Time{})
	return conn, nil
}

// acceptPreamble checks a new connection's preamble against token and acks it,
// and reports whether it opens a control connection rather than a session.
// The token is compared in constant time, and the whole preamble must arrive
// within helloTimeout, so a silent connection cannot pin a goroutine. On any
// error the caller closes the connection without a word.
func acceptPreamble(conn net.Conn, token []byte) (control bool, err error) {
	magic, err := acceptMagics(conn, token, map[string]string{preambleMagic: preambleAck, controlMagic: controlAck})
	return magic == controlMagic, err
}

// acceptMagics is acceptPreamble for any set of magics, each with its ack (all
// of the same length): it returns the magic the connection opened with.
func acceptMagics(conn net.Conn, token []byte, acks map[string]string) (string, error) {
	_ = conn.SetDeadline(time.Now().Add(helloTimeout))
	pre := make([]byte, len(preambleMagic)+tokenLen)
	if _, err := io.ReadFull(conn, pre); err != nil {
		return "", err
	}
	magic := string(pre[:len(preambleMagic)])
	ack, magicOK := acks[magic]
	tokenOK := subtle.ConstantTimeCompare(pre[len(preambleMagic):], token) == 1
	if !magicOK || !tokenOK {
		return "", errRejected
	}
	if _, err := conn.Write([]byte(ack)); err != nil {
		return "", err
	}
	_ = conn.SetDeadline(time.Time{})
	return magic, nil
}
