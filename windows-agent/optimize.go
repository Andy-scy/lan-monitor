package main

// optimize.go — 一键内存优化：EmptyWorkingSet 整理所有可访问进程的工作集。
// 用户态、免管理员；受保护/系统进程会跳过并如实计数。不做任何"加速"魔术。

import (
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	psapiDll         = windows.NewLazySystemDLL("psapi.dll")
	pEnumProcesses   = psapiDll.NewProc("EnumProcesses")
	pEmptyWorkingSet = psapiDll.NewProc("EmptyWorkingSet")
	pOpenProcess     = kernel32.NewProc("OpenProcess")
	pCloseHandle     = kernel32.NewProc("CloseHandle")
)

const (
	processSetQuota          = 0x0100
	processQueryInformation  = 0x0400
)

type optimizeResult struct {
	BeforeAvailMB float64 `json:"beforeAvailMB"`
	AfterAvailMB  float64 `json:"afterAvailMB"`
	FreedMB       float64 `json:"freedMB"`
	Swept         int     `json:"swept"`
	Skipped       int     `json:"skipped"`
}

func availPhysMB() float64 {
	var ms memoryStatusEx
	ms.cb = uint32(unsafe.Sizeof(ms))
	if r, _, _ := procGlobalMemoryStatusEx.Call(uintptr(unsafe.Pointer(&ms))); r == 0 {
		return 0
	}
	return float64(ms.ullAvailPhys) / 1048576
}

// sweepWorkingSets 遍历进程整理工作集，返回 (成功, 跳过)
func sweepWorkingSets() (int, int) {
	ids := make([]uint32, 8192)
	var needed uint32
	if r, _, _ := pEnumProcesses.Call(uintptr(unsafe.Pointer(&ids[0])), uintptr(len(ids))*4,
		uintptr(unsafe.Pointer(&needed))); r == 0 {
		return 0, 0
	}
	n := int(needed) / 4
	if n > len(ids) {
		n = len(ids)
	}
	me := windows.GetCurrentProcessId()
	swept, skipped := 0, 0
	for i := 0; i < n; i++ {
		pid := ids[i]
		if pid == 0 || pid == me {
			continue
		}
		h, _, _ := pOpenProcess.Call(processSetQuota|processQueryInformation, 0, uintptr(pid))
		if h == 0 {
			skipped++
			continue
		}
		if r, _, _ := pEmptyWorkingSet.Call(h); r != 0 {
			swept++
		} else {
			skipped++
		}
		pCloseHandle.Call(h)
	}
	return swept, skipped
}

// runOptimize 执行内存整理并返回前后对比（自身进程最后整理）
func runOptimize() optimizeResult {
	before := availPhysMB()
	swept, skipped := sweepWorkingSets()
	pEmptyWorkingSet.Call(uintptr(windows.CurrentProcess()))
	after := availPhysMB()
	freed := after - before
	if freed < 0 {
		freed = 0
	}
	return optimizeResult{
		BeforeAvailMB: float64(int(before*10)) / 10,
		AfterAvailMB:  float64(int(after*10)) / 10,
		FreedMB:       float64(int(freed*10)) / 10,
		Swept:         swept,
		Skipped:       skipped,
	}
}
