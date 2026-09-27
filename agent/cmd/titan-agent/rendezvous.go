package main

import (
	"bytes"
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
	token, err := st.token()
	if err != nil {
		return nil, err
	}
	conn, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(st.Port)), dialTimeout)
	if err != nil {
		return nil, err
	}
	_ = conn.SetDeadline(time.Now().Add(dialTimeout))
	pre := append([]byte(preambleMagic), token...)
	ack := make([]byte, len(preambleAck))
	if _, err := conn.Write(pre); err != nil {
		conn.Close()
		return nil, errRejected
	}
	if _, err := io.ReadFull(conn, ack); err != nil || string(ack) != preambleAck {
		conn.Close()
		return nil, errRejected
	}
	_ = conn.SetDeadline(time.Time{})
	return conn, nil
}

// acceptPreamble checks a new connection's preamble against token and acks it.
// The token is compared in constant time, and the whole preamble must arrive
// within helloTimeout, so a silent connection cannot pin a goroutine. On any
// error the caller closes the connection without a word.
func acceptPreamble(conn net.Conn, token []byte) error {
	_ = conn.SetDeadline(time.Now().Add(helloTimeout))
	pre := make([]byte, len(preambleMagic)+tokenLen)
	if _, err := io.ReadFull(conn, pre); err != nil {
		return err
	}
	magicOK := bytes.Equal(pre[:len(preambleMagic)], []byte(preambleMagic))
	tokenOK := subtle.ConstantTimeCompare(pre[len(preambleMagic):], token) == 1
	if !magicOK || !tokenOK {
		return errRejected
	}
	if _, err := conn.Write([]byte(preambleAck)); err != nil {
		return err
	}
	_ = conn.SetDeadline(time.Time{})
	return nil
}
