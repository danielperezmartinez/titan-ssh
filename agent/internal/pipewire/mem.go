//go:build linux

package pipewire

import (
	"fmt"

	"golang.org/x/sys/unix"
)

// Memory types of add_mem (spa_data_type).
const (
	dataMemPtr = 1
	dataMemFd  = 2
	dataDmaBuf = 3
	dataMemID  = 4
)

// memBlock is a block of shared memory the server announced with add_mem:
// a memfd (or SHM file) that buffers, io areas and activations live in.
type memBlock struct {
	id, typ, flags uint32
	fd             int
	data           []byte
}

// bytes maps the block (once, whole) and returns size bytes at offset.
func (m *memBlock) bytes(offset, size uint32) ([]byte, error) {
	if m.typ == dataDmaBuf {
		return nil, fmt.Errorf("pipewire: memory %d is a DMA-BUF, which this client does not map", m.id)
	}
	if m.data == nil {
		var st unix.Stat_t
		if err := unix.Fstat(m.fd, &st); err != nil {
			return nil, fmt.Errorf("pipewire: memory %d: %w", m.id, err)
		}
		if st.Size <= 0 {
			return nil, fmt.Errorf("pipewire: memory %d is empty", m.id)
		}
		data, err := unix.Mmap(m.fd, 0, int(st.Size), unix.PROT_READ|unix.PROT_WRITE, unix.MAP_SHARED)
		if err != nil {
			// Sealed or read-only memory (frame data): reading is enough.
			if data, err = unix.Mmap(m.fd, 0, int(st.Size), unix.PROT_READ, unix.MAP_SHARED); err != nil {
				return nil, fmt.Errorf("pipewire: map memory %d: %w", m.id, err)
			}
		}
		m.data = data
	}
	end := uint64(offset) + uint64(size)
	if end > uint64(len(m.data)) {
		return nil, fmt.Errorf("pipewire: memory %d is %d bytes, %d+%d asked", m.id, len(m.data), offset, size)
	}
	return m.data[offset:end:end], nil
}

func (m *memBlock) close() {
	if m.data != nil {
		_ = unix.Munmap(m.data)
		m.data = nil
	}
	if m.fd >= 0 {
		_ = unix.Close(m.fd)
		m.fd = -1
	}
}
