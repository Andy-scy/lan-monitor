package main

// nvml.go — NVIDIA NVML 动态加载封装（纯 syscall，无 CGO）。
// nvml.dll 随 NVIDIA 驱动分发；找不到时 NVIDIA 卡的温度/功耗/频率为 null。

import (
	"sync"
	"unsafe"

	"golang.org/x/sys/windows"
)

type nvmlAPI struct {
	mu       sync.Mutex
	dll      *windows.DLL
	devs     []*nvmlDevice
	pGetCount  *windows.Proc
	pGetHandle *windows.Proc
}

type nvmlDevice struct {
	api    *nvmlAPI
	handle uintptr
	name   string
	pName  *windows.Proc
	pTemp  *windows.Proc
	pUtil  *windows.Proc
	pMem   *windows.Proc
	pPower *windows.Proc
	pClock *windows.Proc
}

var (
	nvmlProcInit    = "nvmlInit_v2"
	nvmlProcShutdown = "nvmlShutdown"
)

func findNVMLDLL() string {
	candidates := []string{
		`C:\Windows\System32\nvml.dll`,
		`C:\Windows\SysWOW64\nvml.dll`,
		`C:\Program Files\NVIDIA Corporation\NVSMI\nvml.dll`,
	}
	for _, c := range candidates {
		if fileExists(c) {
			return c
		}
	}
	return ""
}

func openNVML() *nvmlAPI {
	path := findNVMLDLL()
	if path == "" {
		dbg("nvml.dll not found")
		return nil
	}
	defer func() {
		if r := recover(); r != nil {
			dbg("nvml load panic: %v", r)
		}
	}()
	dll, err := windows.LoadDLL(path)
	if err != nil {
		dbg("nvml load err: %v", err)
		return nil
	}
	procInit, e1 := dll.FindProc(nvmlProcInit)
	if e1 != nil {
		return nil
	}
	if r, _, _ := procInit.Call(); r != 0 {
		dbg("nvmlInit: %d", r)
		return nil
	}
	api := &nvmlAPI{dll: dll}
	api.pGetCount, _ = dll.FindProc("nvmlDeviceGetCount_v2")
	api.pGetHandle, _ = dll.FindProc("nvmlDeviceGetHandleByIndex_v2")
	var n uint32
	if api.pGetCount == nil ||
		r0(mustCall(api.pGetCount, uintptr(unsafe.Pointer(&n)))) != 0 || n == 0 {
		return api
	}
	pName, _ := dll.FindProc("nvmlDeviceGetName")
	pTemp, _ := dll.FindProc("nvmlDeviceGetTemperature")
	pUtil, _ := dll.FindProc("nvmlDeviceGetUtilizationRates")
	pMem, _ := dll.FindProc("nvmlDeviceGetMemoryInfo")
	pPower, _ := dll.FindProc("nvmlDeviceGetPowerUsage")
	pClock, _ := dll.FindProc("nvmlDeviceGetClockInfo")
	for i := uint32(0); i < n && i < 8; i++ {
		var h uintptr
		if r0(mustCall(api.pGetHandle, uintptr(i), uintptr(unsafe.Pointer(&h)))) != 0 {
			continue
		}
		d := &nvmlDevice{api: api, handle: h, pName: pName, pTemp: pTemp, pUtil: pUtil,
			pMem: pMem, pPower: pPower, pClock: pClock}
		if pName != nil {
			buf := make([]byte, 96)
			if r0(mustCall(pName, h, uintptr(unsafe.Pointer(&buf[0])), 96)) == 0 {
				d.name = cstr(buf)
			}
		}
		api.devs = append(api.devs, d)
	}
	return api
}

// mustCall 防 NIL panic（FindProc 失败返回 nil）
func mustCall(p *windows.Proc, args ...uintptr) uintptr {
	if p == nil {
		return 0xFFFFFFFF
	}
	r, _, _ := p.Call(args...)
	return r
}

// fill 把 NVML 指标写入 GPU 快照（仅 NVIDIA 可得）
func (d *nvmlDevice) fill(g *GPU) {
	if d == nil || d.api == nil {
		return
	}
	d.api.mu.Lock()
	defer d.api.mu.Unlock()
	if g.Name == nil || *g.Name == "" {
		n := d.name
		if n != "" {
			g.Name = &n
		}
	}
	if d.pTemp != nil {
		var t uint32
		if r0(mustCall(d.pTemp, d.handle, 0 /*NVML_TEMPERATURE_GPU*/, uintptr(unsafe.Pointer(&t)))) == 0 && t > 0 && t < 120 {
			tv := float64(t)
			g.Temp = &tv
		}
	}
	type nvmlUtil struct{ gpu, mem uint32 }
	if d.pUtil != nil {
		var u nvmlUtil
		if r0(mustCall(d.pUtil, d.handle, uintptr(unsafe.Pointer(&u)))) == 0 && u.gpu <= 100 {
			uv := float64(u.gpu)
			g.Usage = &uv
		}
	}
	type nvmlMem struct{ total, free, used uint64 }
	if d.pMem != nil {
		var m nvmlMem
		if r0(mustCall(d.pMem, d.handle, uintptr(unsafe.Pointer(&m)))) == 0 && m.total > 0 {
			used := bytesToMB(float64(m.used))
			total := bytesToMB(float64(m.total))
			g.MemUsedMB = &used
			g.MemTotalMB = &total
		}
	}
	if d.pPower != nil {
		var mw uint32
		if r0(mustCall(d.pPower, d.handle, uintptr(unsafe.Pointer(&mw)))) == 0 && mw > 0 {
			w := float64(mw) / 1000.0
			g.PowerW = &w
		}
	}
	if d.pClock != nil {
		var mhz uint32
		if r0(mustCall(d.pClock, d.handle, 0 /*NVML_CLOCK_GRAPHICS*/, uintptr(unsafe.Pointer(&mhz)))) == 0 && mhz > 0 {
			f := float64(mhz)
			g.ClockMhz = &f
		}
	}
}

func (a *nvmlAPI) devices() []*nvmlDevice { return a.devs }

func r0(r uintptr) uintptr { return r }

func cstr(b []byte) string {
	for i, c := range b {
		if c == 0 {
			return string(b[:i])
		}
	}
	return string(b)
}
