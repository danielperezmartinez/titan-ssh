package main

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"unsafe"

	"golang.org/x/sys/windows"
)

// The state dir holds the tokens that let whoever reads them drive the user's
// shells (agent.json) and desktop (desktop.json), and the helper executable
// the scheduled task runs. So that it does not depend on the ACL it would
// inherit, the agent creates it, and the files that matter in it, with an
// explicit protected DACL: only the user and SYSTEM have access. A mandatory
// label with no-read-up also keeps the user's own lower-integrity processes
// (some sandboxes) from reading them, which Windows allows by default.

// currentUserSID is the SID of the account this process runs as.
func currentUserSID() (*windows.SID, error) {
	tu, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		return nil, err
	}
	return tu.User.Sid.Copy()
}

// privateSecurity is a security descriptor that gives only the user and
// SYSTEM full access, inherited by what is created inside when inherit is set
// (a directory), and with the no-read-up label when label is set.
func privateSecurity(inherit, label bool) (*windows.SecurityAttributes, error) {
	sid, err := currentUserSID()
	if err != nil {
		return nil, err
	}
	flags := ""
	if inherit {
		flags = "OICI"
	}
	sddl := fmt.Sprintf("D:P(A;%[1]s;FA;;;%[2]s)(A;%[1]s;FA;;;SY)", flags, sid)
	if label {
		sddl += fmt.Sprintf("S:(ML;%s;NRNW;;;ME)", flags)
	}
	sd, err := windows.SecurityDescriptorFromString(sddl)
	if err != nil {
		return nil, err
	}
	return &windows.SecurityAttributes{
		Length:             uint32(unsafe.Sizeof(windows.SecurityAttributes{})),
		SecurityDescriptor: sd,
	}, nil
}

// createPrivate runs create with the private security descriptor, and again
// without the label if Windows refuses it (a process below medium integrity
// cannot set it). An exists error is returned as it is.
func createPrivate(inherit bool, exists error, create func(*windows.SecurityAttributes) error) error {
	var err error
	for _, label := range []bool{true, false} {
		sa, serr := privateSecurity(inherit, label)
		if serr != nil {
			return serr
		}
		if err = create(sa); err == nil || errors.Is(err, exists) {
			return err
		}
	}
	return err
}

// createPrivateDir creates dir, whose parent must exist, with the private
// security descriptor, inherited by everything created in it. It fails with
// windows.ERROR_ALREADY_EXISTS if something is already there.
func createPrivateDir(dir string) error {
	p, err := windows.UTF16PtrFromString(dir)
	if err != nil {
		return err
	}
	return createPrivate(true, windows.ERROR_ALREADY_EXISTS, func(sa *windows.SecurityAttributes) error {
		return windows.CreateDirectory(p, sa)
	})
}

// createPrivateTemp is os.CreateTemp for a file with the private security
// descriptor of its own, whatever dir would pass on to it. It keeps it when
// renamed within the volume.
func createPrivateTemp(dir, pattern string) (*os.File, error) {
	prefix, suffix := pattern, ""
	if i := strings.LastIndex(pattern, "*"); i >= 0 {
		prefix, suffix = pattern[:i], pattern[i+1:]
	}
	for range 100 {
		random := make([]byte, 8)
		if _, err := rand.Read(random); err != nil {
			return nil, err
		}
		name := filepath.Join(dir, prefix+hex.EncodeToString(random)+suffix)
		p, err := windows.UTF16PtrFromString(name)
		if err != nil {
			return nil, err
		}
		var h windows.Handle
		err = createPrivate(false, windows.ERROR_FILE_EXISTS, func(sa *windows.SecurityAttributes) error {
			var cerr error
			h, cerr = windows.CreateFile(p, windows.GENERIC_READ|windows.GENERIC_WRITE,
				windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE, sa,
				windows.CREATE_NEW, windows.FILE_ATTRIBUTE_NORMAL, 0)
			return cerr
		})
		if errors.Is(err, windows.ERROR_FILE_EXISTS) {
			continue
		}
		if err != nil {
			return nil, &os.PathError{Op: "createtemp", Path: name, Err: err}
		}
		return os.NewFile(uintptr(h), name), nil
	}
	return nil, &os.PathError{Op: "createtemp", Path: filepath.Join(dir, pattern), Err: os.ErrExist}
}

// The kinds of DACL entry that deny access, which checkPrivateDir lets
// through. It refuses any other kind but a plain allow.
const (
	accessDeniedObjectACE         = 0x6
	accessDeniedCallbackACE       = 0xA
	accessDeniedCallbackObjectACE = 0xC
)

// checkPrivateDir refuses dir unless it is owned by the user (or by
// Administrators, the owner of what an elevated administrator creates) and its
// DACL lets in nobody but the user, SYSTEM and Administrators. Administrators
// can take any file anyway, and SYSTEM is the system itself; anyone else could
// read the token or plant files the agent would trust. The default dir, in the
// user's profile, has just those entries even when an older agent created it.
func checkPrivateDir(dir string) error {
	sd, err := windows.GetNamedSecurityInfo(dir, windows.SE_FILE_OBJECT,
		windows.OWNER_SECURITY_INFORMATION|windows.DACL_SECURITY_INFORMATION)
	if err != nil {
		return fmt.Errorf("state dir %s: cannot read its permissions: %w", dir, err)
	}
	user, err := currentUserSID()
	if err != nil {
		return err
	}
	admins, err := windows.CreateWellKnownSid(windows.WinBuiltinAdministratorsSid)
	if err != nil {
		return err
	}
	system, err := windows.CreateWellKnownSid(windows.WinLocalSystemSid)
	if err != nil {
		return err
	}
	creator, err := windows.CreateWellKnownSid(windows.WinCreatorOwnerSid)
	if err != nil {
		return err
	}
	owner, _, err := sd.Owner()
	if err != nil {
		return fmt.Errorf("state dir %s: cannot read its owner: %w", dir, err)
	}
	if !owner.Equals(user) && !owner.Equals(admins) {
		return fmt.Errorf("state dir %s is owned by %s, not by this user", dir, accountName(owner))
	}
	dacl, _, err := sd.DACL()
	if err != nil || dacl == nil {
		return fmt.Errorf("state dir %s has no access list, so everyone can use it", dir)
	}
	for i := range uint32(dacl.AceCount) {
		var ace *windows.ACCESS_ALLOWED_ACE
		if err := windows.GetAce(dacl, i, &ace); err != nil {
			return fmt.Errorf("state dir %s: cannot read its access list: %w", dir, err)
		}
		switch ace.Header.AceType {
		case windows.ACCESS_DENIED_ACE_TYPE, accessDeniedObjectACE, accessDeniedCallbackACE, accessDeniedCallbackObjectACE:
			continue
		case windows.ACCESS_ALLOWED_ACE_TYPE:
		default:
			return fmt.Errorf("state dir %s has an access entry of an unexpected kind (%d)", dir, ace.Header.AceType)
		}
		sid := (*windows.SID)(unsafe.Pointer(&ace.SidStart))
		// CREATOR OWNER stands for whoever creates a file inside, who already
		// needs another entry to be able to.
		if !sid.Equals(user) && !sid.Equals(system) && !sid.Equals(admins) && !sid.Equals(creator) {
			return fmt.Errorf("state dir %s gives access to %s: only this user, SYSTEM and Administrators may have it", dir, accountName(sid))
		}
	}
	return nil
}

// accountName is sid as DOMAIN\name, or as a SID string when it has no name.
func accountName(sid *windows.SID) string {
	account, domain, _, err := sid.LookupAccount("")
	if err != nil {
		return sid.String()
	}
	if domain == "" {
		return account
	}
	return domain + `\` + account
}
