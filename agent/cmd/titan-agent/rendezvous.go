package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"errors"
	"io"
	"net"
	"slices"
	"strconv"
	"time"
)

// The front↔daemon hop runs over TCP on 127.0.0.1 (ADR-0009 §2). Before any
// byte of the client protocol the two ends prove to each other that they know
// the token from the state file, without sending it:
//
//	front  → daemon  magic ‖ nonceF
//	daemon → front   nonceD ‖ HMAC(token, "server" ‖ magic ‖ nonceF ‖ nonceD)
//	front  → daemon  HMAC(token, "client" ‖ magic ‖ nonceF ‖ nonceD)
//
// The front checks the daemon's proof before it sends anything else, so the
// client's bytes only ever reach a process that holds this daemon's token, and
// the token itself never crosses the socket. The token never leaves the host
// either: the client↔agent protocol on the SSH channel is unchanged, and only
// the front, on the destination, knows it.
//
// Each kind of connection has its own magic, which the proofs cover: a session,
// a control request (control.go), and the desktop helper's input, control and
// run connections (desktop.go, desktoprun.go), which have their own listener
// and token.
const (
	preambleMagic       = "TTNAGNT2"
	controlMagic        = "TTNACTL2"
	desktopMagic        = "TTNADSK2"
	desktopControlMagic = "TTNADCT2"
	desktopRunMagic     = "TTNADRN2"
)

// The first handshake sent the raw token and took a fixed ack back. Agents up
// to 0.1.0-beta.10 speak only that one. Their daemons and helpers still accept
// it, so a client that has not been updated keeps working; a current client
// never opens a session with it, and only uses it to tell an older daemon or
// helper apart and to stop it (see legacyAllowed).
const (
	legacyPreambleMagic       = "TTNAGNT1"
	legacyPreambleAck         = "TTNAGOK1"
	legacyControlMagic        = "TTNACTL1"
	legacyControlAck          = "TTNACOK1"
	legacyDesktopMagic        = "TTNADSK1"
	legacyDesktopAck          = "TTNADOK1"
	legacyDesktopControlMagic = "TTNADCT1"
	legacyDesktopControlAck   = "TTNADCO1"
)

const (
	magicLen = 8
	nonceLen = 32 // the same length as the token, so both handshakes open with 40 bytes
	proofLen = sha256.Size
)

// dialTimeout bounds the connect plus the handshake. A var so tests can
// shorten it.
var dialTimeout = 3 * time.Second

// handshakeTimeout bounds the listening side of the handshake. The front gives
// up after dialTimeout, so waiting longer than that serves nobody. A var so
// tests can shorten it.
var handshakeTimeout = 3 * time.Second

// maxHandshakes bounds the connections a listener holds before they have
// proved they know the token. Any local account can connect to the port; past
// this many, a new connection is closed at once, so a flood of silent ones
// costs a bounded number of descriptors and goroutines and never the live
// sessions. A real front takes a few milliseconds to get through.
const maxHandshakes = 32

// handshakeSlots counts a listener's connections still in their handshake.
type handshakeSlots chan struct{}

func newHandshakeSlots() handshakeSlots { return make(handshakeSlots, maxHandshakes) }

// take reserves a slot, or reports false when all of them are in use.
func (s handshakeSlots) take() bool {
	select {
	case s <- struct{}{}:
		return true
	default:
		return false
	}
}

func (s handshakeSlots) release() { <-s }

// errRejected reports that something listens on the published port but did
// not prove it holds the token: a stale state file whose port now belongs to
// someone else, a daemon holding another token, or one that predates this
// handshake.
var errRejected = errors.New("the daemon did not prove it holds the token")

// dialDaemon connects to the daemon st describes for a session.
func dialDaemon(st agentState) (net.Conn, error) {
	return dialWith(st, preambleMagic)
}

// dialControl is dialDaemon for a control connection (control.go).
func dialControl(st agentState) (net.Conn, error) {
	return dialWith(st, controlMagic)
}

// dialWith connects to the listener st describes and runs the handshake for
// magic. It returns errRejected unless the peer proves it holds st's token.
func dialWith(st agentState, magic string) (net.Conn, error) {
	token, err := st.token()
	if err != nil {
		return nil, err
	}
	conn, err := dialLoopback(st.Port)
	if err != nil {
		return nil, err
	}
	nonceF := make([]byte, nonceLen)
	if _, err := rand.Read(nonceF); err != nil {
		conn.Close()
		return nil, err
	}
	if _, err := conn.Write(append([]byte(magic), nonceF...)); err != nil {
		conn.Close()
		return nil, errRejected
	}
	reply := make([]byte, nonceLen+proofLen)
	if _, err := io.ReadFull(conn, reply); err != nil {
		conn.Close()
		return nil, errRejected
	}
	nonceD, proof := reply[:nonceLen], reply[nonceLen:]
	if !hmac.Equal(proof, handshakeProof(token, roleServer, magic, nonceF, nonceD)) {
		conn.Close()
		return nil, errRejected
	}
	if _, err := conn.Write(handshakeProof(token, roleClient, magic, nonceF, nonceD)); err != nil {
		conn.Close()
		return nil, errRejected
	}
	_ = conn.SetDeadline(time.Time{})
	return conn, nil
}

// dialLegacy runs the first handshake, which sends the token: only for a peer
// that legacyAllowed vouches for.
func dialLegacy(st agentState, magic, wantAck string) (net.Conn, error) {
	token, err := st.token()
	if err != nil {
		return nil, err
	}
	conn, err := dialLoopback(st.Port)
	if err != nil {
		return nil, err
	}
	ack := make([]byte, len(wantAck))
	if _, err := conn.Write(append([]byte(magic), token...)); err != nil {
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

// legacyAllowed reports whether the first handshake may be used against the
// listener of the record called name, guarded by the lock called lock: only
// while a live process of this user holds that lock, which is when the
// record's port is still the one it listens on. A record left behind by a
// process that died is never dialed that way.
func legacyAllowed(dir, lock string) bool {
	held, err := lockNamedHeld(dir, lock)
	return err == nil && held
}

func dialLoopback(port int) (net.Conn, error) {
	conn, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(port)), dialTimeout)
	if err != nil {
		return nil, err
	}
	_ = conn.SetDeadline(time.Now().Add(dialTimeout))
	return conn, nil
}

// The two proofs of a handshake differ in their role, so neither can stand in
// for the other.
const (
	roleServer = "titan-agent server"
	roleClient = "titan-agent client"
)

func handshakeProof(token []byte, role, magic string, nonceF, nonceD []byte) []byte {
	mac := hmac.New(sha256.New, token)
	mac.Write([]byte(role))
	mac.Write([]byte{0})
	mac.Write([]byte(magic))
	mac.Write(nonceF)
	mac.Write(nonceD)
	return mac.Sum(nil)
}

// acceptPreamble runs the daemon's side of the handshake on a new connection
// and reports whether it opens a control connection rather than a session. On
// any error the caller closes the connection without a word.
func acceptPreamble(conn net.Conn, token []byte) (control bool, err error) {
	magic, err := acceptMagics(conn, token,
		[]string{preambleMagic, controlMagic},
		map[string]string{legacyPreambleMagic: legacyPreambleAck, legacyControlMagic: legacyControlAck})
	return magic == controlMagic || magic == legacyControlMagic, err
}

// acceptMagics is the listening side of the handshake for any of magics, and
// of the first handshake for any of the legacy ones (each with its ack). It
// returns the magic the connection opened with. The whole handshake must
// complete within handshakeTimeout, so a silent connection cannot pin a
// goroutine, and the peer's proof (or token) is compared in constant time.
func acceptMagics(conn net.Conn, token []byte, magics []string, legacy map[string]string) (string, error) {
	_ = conn.SetDeadline(time.Now().Add(handshakeTimeout))
	pre := make([]byte, magicLen+nonceLen)
	if _, err := io.ReadFull(conn, pre); err != nil {
		return "", err
	}
	magic, rest := string(pre[:magicLen]), pre[magicLen:]
	switch ack, isLegacy := legacy[magic]; {
	case slices.Contains(magics, magic):
		nonceD := make([]byte, nonceLen)
		if _, err := rand.Read(nonceD); err != nil {
			return "", err
		}
		reply := append(nonceD, handshakeProof(token, roleServer, magic, rest, nonceD)...)
		if _, err := conn.Write(reply); err != nil {
			return "", err
		}
		proof := make([]byte, proofLen)
		if _, err := io.ReadFull(conn, proof); err != nil {
			return "", err
		}
		if !hmac.Equal(proof, handshakeProof(token, roleClient, magic, rest, nonceD)) {
			return "", errRejected
		}
	case isLegacy:
		if subtle.ConstantTimeCompare(rest, token) != 1 {
			return "", errRejected
		}
		if _, err := conn.Write([]byte(ack)); err != nil {
			return "", err
		}
	default:
		return "", errRejected
	}
	_ = conn.SetDeadline(time.Time{})
	return magic, nil
}
