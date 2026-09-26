package main

// mem_disk.go — 内存（GlobalMemoryStatusEx + GetPerformanceInfo）与磁盘（容量 + 活动）采集。

import (
	"sort"
	"strings"
	"unsafe"

	"golang.org/x/sys/windows"
)

type memoryStatusEx struct {
	cb                   uint32
	dwMemoryLoad         uint32
	ullTotalPhys         uint64
	ullAvailPhys         uint64
	ullTotalPageFile     uint64
	ullAvailPageFile     uint64
	ullTotalVirtual      uint64
	ullAvailVirtual      uint64
	ullAvailExtendedVirtual uint64
}

type performanceInformation struct {
	cb                uint32
	CommitTotal       uintptr
	CommitLimit       uintptr
	CommitPeak        uintptr
	PhysicalTotal     uintptr
	PhysicalAvailable uintptr
	SystemCache       uintptr
	KernelTotal       uintptr
	KernelPaged       uintptr
	KernelNonPaged    uintptr
	PageSize          uintptr
	HandleCount       uintptr
	ProcessCount      uintptr
	ThreadCount       uintptr
}

var procGetPerformanceInfo = windows.NewLazySystemDLL("psapi.dll").NewProc("GetPerformanceInfo")

func memSnapshot() *Memory {
	var ms memoryStatusEx
	ms.cb = uint32(unsafe.Sizeof(ms))
	if r, _, _ := procGlobalMemoryStatusEx.Call(uintptr(unsafe.Pointer(&ms))); r == 0 {
		return nil
	}
	m := &Memory{}
	total := bytesToMB(float64(ms.ullTotalPhys))
	avail := bytesToMB(float64(ms.ullAvailPhys))
	used := bytesToMB(float64(ms.ullTotalPhys - ms.ullAvailPhys))
	m.TotalMB, m.AvailableMB, m.UsedMB = &total, &avail, &used
	if ms.ullTotalPhys > 0 {
		u := clampPct(float64(ms.ullTotalPhys-ms.ullAvailPhys) / float64(ms.ullTotalPhys) * 100)
		m.UsagePct = &u
	}
	var pi performanceInformation
	pi.cb = uint32(unsafe.Sizeof(pi))
	if r, _, _ := procGetPerformanceInfo.Call(uintptr(unsafe.Pointer(&pi)), uintptr(pi.cb)); r != 0 && pi.PageSize > 0 {
		pg := float64(pi.PageSize)
		cu := bytesToMB(float64(pi.CommitTotal) * pg)
		cl := bytesToMB(float64(pi.CommitLimit) * pg)
		m.CommitUsedMB, m.CommitLimitMB = &cu, &cl
	}
	return m
}

// ---------- 磁盘 ----------

var (
	procGetLogicalDriveStringsW = kernel32.NewProc("GetLogicalDriveStringsW")
	procGetVolumeInformationW   = kernel32.NewProc("GetVolumeInformationW")
)

func diskSnapshot(q *pdhQuery) []*Disk {
	buf := make([]uint16, 512)
	n, _, _ := procGetLogicalDriveStringsW.Call(uintptr(len(buf)), uintptr(unsafe.Pointer(&buf[0])))
	if n == 0 || int(n) > len(buf) {
		return nil
	}
	var diskRead, diskWrite map[string]float64
	if q != nil {
		diskRead = q.readArray(`\PhysicalDisk(*)\Disk Read Bytes/sec`)
		diskWrite = q.readArray(`\PhysicalDisk(*)\Disk Write Bytes/sec`)
	}
	var out []*Disk
	for _, drive := range splitDriveStrings(buf[:n]) {
		typ, _, _ := procGetDriveType.Call(uintptr(unsafe.Pointer(windows.StringToUTF16Ptr(drive))))
		if typ == windows.DRIVE_CDROM || typ == windows.DRIVE_NO_ROOT_DIR || typ == 0 {
			continue
		}
		var freeBytes, totalBytes, availBytes uint64
		p := windows.StringToUTF16Ptr(drive)
		if r, _, _ := procGetDiskFreeSpaceEx.Call(uintptr(unsafe.Pointer(p)),
			uintptr(unsafe.Pointer(&availBytes)), uintptr(unsafe.Pointer(&totalBytes)), uintptr(unsafe.Pointer(&freeBytes))); r == 0 {
			continue
		}
		if totalBytes == 0 {
			continue
		}
		d := &Disk{Drive: strings.TrimSuffix(drive, `\`), Removable: typ == windows.DRIVE_REMOVABLE}
		label := volumeLabel(drive)
		if label != "" {
			d.Label = &label
		}
		t := bytesToMB(float64(totalBytes))
		f := bytesToMB(float64(freeBytes))
		u := bytesToMB(float64(totalBytes - freeBytes))
		pct := clampPct(float64(totalBytes-freeBytes) / float64(totalBytes) * 100)
		d.TotalMB, d.FreeMB, d.UsedMB, d.UsagePct = &t, &f, &u, &pct
		if rb, ok := matchDiskRate(diskRead, d.Drive); ok {
			v := rb
			d.ReadBps = &v
		}
		if wb, ok := matchDiskRate(diskWrite, d.Drive); ok {
			v := wb
			d.WriteBps = &v
		}
		out = append(out, d)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Drive < out[j].Drive })
	return out
}

// matchDiskRate: PhysicalDisk 实例名形如 "0 C:" 或 "1 C: D:"，按卷字母匹配
func matchDiskRate(m map[string]float64, drive string) (float64, bool) {
	if len(m) == 0 {
		return 0, false
	}
	letter := strings.TrimSuffix(drive, ":")
	for inst, v := range m {
		for _, tok := range strings.Fields(inst) {
			if tok == letter || tok == letter+":" {
				if !isNan(v) && v >= 0 {
					return v, true
				}
			}
		}
	}
	return 0, false
}

func splitDriveStrings(buf []uint16) []string {
	var out []string
	start := 0
	for i, c := range buf {
		if c == 0 {
			if i > start {
				out = append(out, windows.UTF16ToString(buf[start:i]))
			}
			start = i + 1
		}
	}
	return out
}

func volumeLabel(root string) string {
	var name [261]uint16
	var fsname [261]uint16
	var flags, maxlen uint32
	p := windows.StringToUTF16Ptr(root)
	if r, _, _ := procGetVolumeInformationW.Call(uintptr(unsafe.Pointer(p)),
		uintptr(unsafe.Pointer(&name[0])), uintptr(len(name)),
		0, uintptr(unsafe.Pointer(&maxlen)), uintptr(unsafe.Pointer(&flags)),
		uintptr(unsafe.Pointer(&fsname[0])), uintptr(len(fsname))); r != 0 {
		return windows.UTF16ToString(name[:])
	}
	return ""
}
