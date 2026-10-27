package x11

import (
	"golang.org/x/sys/unix"
)

// attach creates the segment, maps it here and attaches it to the server.
// The segment is marked for removal once the server has it, so the kernel
// frees it when both sides are gone, even if the agent dies.
func (s *ShmImage) attach() error {
	id, err := unix.SysvShmGet(unix.IPC_PRIVATE, s.size, unix.IPC_CREAT|0o600)
	if err != nil {
		return err
	}
	defer unix.SysvShmCtl(id, unix.IPC_RMID, nil)
	mem, err := unix.SysvShmAttach(id, 0, 0)
	if err != nil {
		return err
	}
	seg, err := s.x.newID()
	if err != nil {
		unix.SysvShmDetach(mem)
		return err
	}
	req := request(16, s.op, shmAttach)
	le.PutUint32(req[4:], seg)
	le.PutUint32(req[8:], uint32(id))
	// read-only (12) = false: the server writes the images
	if err := s.x.sendOnly(req); err == nil {
		err = s.x.Sync()
	}
	if err != nil {
		unix.SysvShmDetach(mem)
		return err
	}
	s.seg, s.mem = seg, mem
	return nil
}

// detach unmaps the segment from this process.
func (s *ShmImage) detach() error {
	if s.mem == nil {
		return nil
	}
	err := unix.SysvShmDetach(s.mem)
	s.mem = nil
	return err
}
