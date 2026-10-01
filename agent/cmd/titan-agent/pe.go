package main

import (
	"encoding/binary"
	"errors"
)

// PE subsystems (IMAGE_OPTIONAL_HEADER.Subsystem).
const (
	peSubsystemGUI     = 2
	peSubsystemConsole = 3
)

// withGUISubsystem returns a copy of the Windows executable exe with its
// subsystem set to GUI and nothing else changed (ADR-0017): Windows then
// starts it without a console window. The agent itself stays a console
// program, so shells such as PowerShell keep its exit codes.
func withGUISubsystem(exe []byte) ([]byte, error) {
	off, err := peSubsystemOffset(exe)
	if err != nil {
		return nil, err
	}
	switch binary.LittleEndian.Uint16(exe[off:]) {
	case peSubsystemConsole, peSubsystemGUI:
	default:
		return nil, errors.New("the executable has an unexpected subsystem")
	}
	out := append([]byte(nil), exe...)
	binary.LittleEndian.PutUint16(out[off:], peSubsystemGUI)
	return out, nil
}

// peSubsystemOffset finds the Subsystem field: the DOS header points to the PE
// signature, followed by the 20-byte COFF header and the optional header,
// where Subsystem sits at offset 68 in both PE32 and PE32+.
func peSubsystemOffset(exe []byte) (int, error) {
	const (
		coffHeaderSize  = 20
		subsystemOffset = 68
		optMagicPE32    = 0x10b
		optMagicPE32Pl  = 0x20b
	)
	if len(exe) < 0x40 || exe[0] != 'M' || exe[1] != 'Z' {
		return 0, errors.New("not a Windows executable")
	}
	pe := int(binary.LittleEndian.Uint32(exe[0x3c:]))
	opt := pe + 4 + coffHeaderSize
	if pe < 0x40 || opt+subsystemOffset+2 > len(exe) || string(exe[pe:pe+4]) != "PE\x00\x00" {
		return 0, errors.New("not a Windows executable")
	}
	switch binary.LittleEndian.Uint16(exe[opt:]) {
	case optMagicPE32, optMagicPE32Pl:
	default:
		return 0, errors.New("the executable has an unknown optional header")
	}
	return opt + subsystemOffset, nil
}
