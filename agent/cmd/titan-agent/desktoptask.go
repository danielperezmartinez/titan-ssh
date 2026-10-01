package main

import (
	"bytes"
	"encoding/binary"
	"encoding/xml"
	"strings"
	"unicode/utf16"
)

// desktopTaskXML is the Task Scheduler definition of the desktop helper's
// task (ADR-0016 §3), as schtasks /create /xml wants it: UTF-16 with a BOM
// (it refuses UTF-8). The task runs only while the user is signed in, on their
// own desktop (InteractiveToken), unelevated, with no time limit, also on
// battery, and never twice at once.
func desktopTaskXML(command string, args []string) []byte {
	quoted := make([]string, len(args))
	for i, a := range args {
		quoted[i] = windowsArg(a)
	}
	doc := `<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo>
    <Description>titan-ssh: lets a mouse pad session on your phone move the pointer and type on this desktop. Remove it from the titan-ssh agent panel.</Description>
  </RegistrationInfo>
  <Principals>
    <Principal id="Author">
      <LogonType>InteractiveToken</LogonType>
      <RunLevel>LeastPrivilege</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <AllowStartOnDemand>true</AllowStartOnDemand>
    <Enabled>true</Enabled>
  </Settings>
  <Actions Context="Author">
    <Exec>
      <Command>` + xmlText(command) + `</Command>
      <Arguments>` + xmlText(strings.Join(quoted, " ")) + `</Arguments>
    </Exec>
  </Actions>
</Task>
`
	units := utf16.Encode([]rune(doc))
	var out bytes.Buffer
	out.Write([]byte{0xff, 0xfe}) // UTF-16LE byte order mark
	_ = binary.Write(&out, binary.LittleEndian, units)
	return out.Bytes()
}

// windowsArg quotes s for a Windows command line the way CommandLineToArgvW
// reads it back (as syscall.EscapeArg does, which only builds on Windows).
func windowsArg(s string) string {
	if s == "" {
		return `""`
	}
	if !strings.ContainsAny(s, " \t\"") {
		return s
	}
	var b strings.Builder
	b.WriteByte('"')
	slashes := 0
	for i := 0; i < len(s); i++ {
		switch c := s[i]; c {
		case '\\':
			slashes++
		case '"':
			// The backslashes before it are written already: double them, and
			// escape the quote.
			b.WriteString(strings.Repeat(`\`, slashes+1))
			slashes = 0
			b.WriteByte(c)
			continue
		default:
			slashes = 0
		}
		b.WriteByte(s[i])
	}
	b.WriteString(strings.Repeat(`\`, slashes)) // double the trailing ones
	b.WriteByte('"')
	return b.String()
}

func xmlText(s string) string {
	var b strings.Builder
	_ = xml.EscapeText(&b, []byte(s))
	return b.String()
}
