package main

// dxgi_gpu.go — 通过 DXGI 枚举显卡（名称/厂商/显存总量/LUID），
// 通过 PDH GPU 计数器（WDDM，跨厂商）获取使用率与显存占用。

import (
	"fmt"
	"strings"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	dxgi                  = windows.NewLazySystemDLL("dxgi.dll")
	procCreateDXGIFactory1 = dxgi.NewProc("CreateDXGIFactory1")
)

var iidIDXGIFactory1 = [16]byte{0x78, 0xAE, 0x0A, 0x77, 0x69, 0xF2, 0xBA, 0x4D, 0xA3, 0xCA, 0x91, 0x88, 0xD5, 0xD8, 0x7F, 0xB5}

const (
	dxgiVendorIntel   = 0x8086
	dxgiVendorNVIDIA  = 0x10DE
	dxgiVendorAMD     = 0x1002
	dxgiVendorAMDR    = 0x1022
	dxgiVendorWarp    = 0x1414
)

type dxgiAdapter struct {
	desc           string
	vendorID       uint32
	deviceID       uint32
	dedicatedBytes uint64
	luidHigh       uint32
	luidLow        uint32
}

func (a *dxgiAdapter) luidID() string { return fmt.Sprintf("luid-0x%08X%08X", a.luidHigh, a.luidLow) }
func (a *dxgiAdapter) vendor() string { return vendorName(a.vendorID) }
func vendorName(v uint32) string {
	switch v {
	case dxgiVendorIntel:
		return "INTEL"
	case dxgiVendorNVIDIA:
		return "NVIDIA"
	case dxgiVendorAMD, dxgiVendorAMDR:
		return "AMD"
	}
	return "OTHER"
}

// DXGI_ADAPTER_DESC（x64, 304B）
type dxgiAdapterDesc struct {
	Description         [128]uint16
	VendorId            uint32
	DeviceId            uint32
	SubSysId            uint32
	Revision            uint32
	DedicatedVideoMemory uintptr
	DedicatedSystemMemory uintptr
	SharedSystemMemory  uintptr
	LuidLow             uint32
	LuidHigh            uint32
}

func enumDXGI() []*dxgiAdapter {
	defer func() {
		if r := recover(); r != nil {
			dbg("dxgi panic: %v", r)
		}
	}()
	factory := uintptr(0)
	r, _, _ := procCreateDXGIFactory1.Call(
		uintptr(unsafe.Pointer(&iidIDXGIFactory1[0])),
		uintptr(unsafe.Pointer(&factory)))
	if r != 0 || factory == 0 {
		dbg("CreateDXGIFactory1 failed: 0x%x", r)
		return nil
	}
	defer vtblRelease(factory)

	var out []*dxgiAdapter
	for i := 0; i < 8; i++ {
		var adapter uintptr
		hr := vtblCall(factory, 3, uintptr(i), uintptr(unsafe.Pointer(&adapter))) // IDXGIFactory::EnumAdapters
		if hr != 0 || adapter == 0 {
			break
		}
		var d dxgiAdapterDesc
		hr2 := vtblCall(adapter, 8, uintptr(unsafe.Pointer(&d))) // IDXGIAdapter::GetDesc
		if hr2 == 0 {
			name := windows.UTF16ToString(d.Description[:])
			low, high := d.LuidLow, d.LuidHigh
			// 排除 WARP / 微软基本显示适配器（LUID 为 0 或厂商 0x1414）
			if d.VendorId != dxgiVendorWarp && !(low == 0 && high == 0) {
				out = append(out, &dxgiAdapter{
					desc:           name,
					vendorID:       d.VendorId,
					deviceID:       d.DeviceId,
					dedicatedBytes: uint64(d.DedicatedVideoMemory),
					luidLow:        low,
					luidHigh:       high,
				})
			}
		}
		vtblRelease(adapter)
	}
	return out
}

// ---- 极简 COM vtable 调用 ----

func vtblCall(obj uintptr, idx uintptr, args ...uintptr) (r1 uintptr) {
	vtbl := *(*uintptr)(unsafe.Pointer(obj))
	fn := *(*uintptr)(unsafe.Add(unsafe.Pointer(vtbl), idx*unsafe.Sizeof(uintptr(0))))
	all := append([]uintptr{obj}, args...)
	r1, _, _ = syscall.SyscallN(fn, all...)
	return
}

func vtblRelease(obj uintptr) uintptr {
	if obj == 0 {
		return 0
	}
	return vtblCall(obj, 2) // IUnknown::Release
}

// ---- PDH GPU 计数器实例名解析 ----

// parseLUIDInstance: "luid_0x00000000_0x0000E1A0_phys_0" → (high, low, true)
func parseLUIDInstance(inst string) ([2]uint32, bool) {
	var out [2]uint32
	i := strings.Index(inst, "luid_")
	if i < 0 {
		return out, false
	}
	parts := strings.Split(inst[i+5:], "_")
	if len(parts) < 2 {
		return out, false
	}
	h, err1 := parseHex32(parts[0])
	l, err2 := parseHex32(parts[1])
	if err1 != nil || err2 != nil {
		return out, false
	}
	out = [2]uint32{h, l}
	return out, true
}

// parseEngineInstance: "pid_1234_luid_0x..._0x..._phys_0_eng_1_engtype_3D" → (luid, pid, engtype, true)
func parseEngineInstance(inst string) ([2]uint32, uint32, string, bool) {
	var luid [2]uint32
	pid := uint32(0)
	if i := strings.Index(inst, "pid_"); i >= 0 {
		rest := inst[i+4:]
		if j := strings.IndexByte(rest, '_'); j > 0 {
			if v, err := parseDec32(rest[:j]); err == nil {
				pid = v
			}
		}
	}
	i := strings.Index(inst, "luid_")
	if i < 0 {
		return luid, pid, "", false
	}
	rest := inst[i+5:]
	parts := strings.SplitN(rest, "_", 4) // high, low, phys_N, eng_N_engtype_X...
	if len(parts) < 2 {
		return luid, pid, "", false
	}
	h, e1 := parseHex32(parts[0])
	l, e2 := parseHex32(parts[1])
	if e1 != nil || e2 != nil {
		return luid, pid, "", false
	}
	luid = [2]uint32{h, l}
	eng := "UNKNOWN"
	if j := strings.Index(rest, "engtype_"); j >= 0 {
		eng = rest[j+len("engtype_"):]
	}
	return luid, pid, eng, true
}

func parseDec32(s string) (uint32, error) {
	if s == "" || len(s) > 10 {
		return 0, fmt.Errorf("bad dec %q", s)
	}
	var v uint64
	for _, ch := range s {
		if ch < '0' || ch > '9' {
			return 0, fmt.Errorf("bad dec %q", s)
		}
		v = v*10 + uint64(ch-'0')
		if v > 0xFFFFFFFF {
			return 0, fmt.Errorf("bad dec %q", s)
		}
	}
	return uint32(v), nil
}

func parseHex32(s string) (uint32, error) {
	s = strings.TrimPrefix(strings.ToLower(strings.TrimSpace(s)), "0x")
	if s == "" || len(s) > 8 {
		return 0, fmt.Errorf("bad hex %q", s)
	}
	var v uint32
	for _, ch := range s {
		var d uint32
		switch {
		case ch >= '0' && ch <= '9':
			d = uint32(ch - '0')
		case ch >= 'a' && ch <= 'f':
			d = uint32(ch-'a') + 10
		default:
			return 0, fmt.Errorf("bad hex %q", s)
		}
		v = v<<4 | d
	}
	return v, nil
}
