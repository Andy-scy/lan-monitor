package main

// gpu.go — GPU 适配器清单：DXGI 优先（名称+专用显存+LUID，最准），
// 失败则回退注册表显示类键（PCI\VEN_ 过滤真实显卡）。虚拟显示适配器一律排除。

import (
	"sort"
	"strings"

	"golang.org/x/sys/windows/registry"
)

const displayClassKey = `SYSTEM\CurrentControlSet\Control\Class\{4d36e968-e325-11ce-bfc1-08002be10318}`

type gpuAdapterInfo struct {
	desc     string
	vendor   string
	vramMB   float64 // 专用显存；未知为 0
	luidLow  uint32
	luidHigh uint32
	hasLuid  bool
}

func registryGPUs() []*gpuAdapterInfo {
	k, err := registry.OpenKey(registry.LOCAL_MACHINE, displayClassKey, registry.ENUMERATE_SUB_KEYS)
	if err != nil {
		return nil
	}
	defer k.Close()
	sub, _ := k.ReadSubKeyNames(-1)
	sort.Strings(sub)
	var out []*gpuAdapterInfo
	for _, s := range sub {
		sk, err := registry.OpenKey(registry.LOCAL_MACHINE, displayClassKey+`\`+s, registry.QUERY_VALUE)
		if err != nil {
			continue
		}
		desc, _, derr := sk.GetStringValue("DriverDesc")
		did, _, _ := sk.GetStringValue("MatchingDeviceId")
		vram, _, verr := sk.GetIntegerValue("HardwareInformation.qwMemorySize")
		sk.Close()
		if derr != nil || desc == "" {
			continue
		}
		// 真实显卡：PCI 枚举的适配器（跳过 IndirectDSP/Root 虚拟显示、Basic Render）
		if !strings.HasPrefix(did, "PCI\\VEN_") {
			continue
		}
		ven := ""
		if rest := strings.TrimPrefix(did, "PCI\\VEN_"); len(rest) >= 4 {
			ven = vendorName(parseHexSafe(rest[:4]))
		}
		g := &gpuAdapterInfo{desc: desc, vendor: ven}
		if verr == nil && vram > 1<<20 {
			g.vramMB = bytesToMB(float64(vram))
		}
		out = append(out, g)
	}
	return out
}

func parseHexSafe(s string) uint32 {
	var v uint32
	for _, ch := range strings.ToLower(s) {
		v <<= 4
		switch {
		case ch >= '0' && ch <= '9':
			v |= uint32(ch - '0')
		case ch >= 'a' && ch <= 'f':
			v |= uint32(ch-'a') + 10
		default:
			return v >> 4
		}
	}
	return v
}
