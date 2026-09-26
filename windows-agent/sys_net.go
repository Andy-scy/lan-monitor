package main

// sys_net.go — 系统信息（版本/主机名/CPU 静态信息/开机时长）与网络速率采集。

import (
	"fmt"
	"math"
	"runtime"
	"sort"
	"strings"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

var kernel32 = windows.NewLazySystemDLL("kernel32.dll")

var (
	procGlobalMemoryStatusEx = kernel32.NewProc("GlobalMemoryStatusEx")
	procGetDiskFreeSpaceEx   = kernel32.NewProc("GetDiskFreeSpaceExW")
	procGetDriveType         = kernel32.NewProc("GetDriveTypeW")
	procGetTickCount64       = kernel32.NewProc("GetTickCount64")
)

func getHostname() string {
	buf := make([]uint16, 64)
	sz := uint32(len(buf))
	if windows.GetComputerNameEx(windows.ComputerNamePhysicalDnsHostname, &buf[0], &sz) == nil {
		return windows.UTF16ToString(buf[:sz])
	}
	return "Windows-PC"
}

func getOSInfo() (name, version string) {
	name = "Windows"
	if k, err := registry.OpenKey(registry.LOCAL_MACHINE,
		`SOFTWARE\Microsoft\Windows NT\CurrentVersion`, registry.QUERY_VALUE); err == nil {
		defer k.Close()
		if v, _, err := k.GetStringValue("ProductName"); err == nil {
			name = strings.TrimSpace(v)
		}
		dv, _, _ := k.GetStringValue("DisplayVersion")
		buildStr, _, _ := k.GetStringValue("CurrentBuildNumber")
		ubr, _, _ := k.GetIntegerValue("UBR")
		if build, err := parseUint(buildStr); err == nil && build >= 22000 {
			name = strings.Replace(name, "Windows 10", "Windows 11", 1)
		}
		version = fmt.Sprintf("%s (build %s.%d)", dv, buildStr, ubr)
	}
	return name, version
}

func parseUint(s string) (int, error) {
	if s == "" {
		return 0, fmt.Errorf("empty")
	}
	n := 0
	for _, ch := range s {
		if ch < '0' || ch > '9' {
			return 0, fmt.Errorf("not a number")
		}
		n = n*10 + int(ch-'0')
	}
	return n, nil
}

func getCPUInfo() (name string, phys, logi int) {
	logi = runtime.NumCPU()
	if k, err := registry.OpenKey(registry.LOCAL_MACHINE,
		`HARDWARE\DESCRIPTION\System\CentralProcessor\0`, registry.QUERY_VALUE); err == nil {
		if v, _, err := k.GetStringValue("ProcessorNameString"); err == nil {
			name = strings.TrimSpace(v)
		}
		k.Close()
	}
	// 物理核数不引入 WMI/驱动，无法可靠获得时置 0（UI 隐藏该行）
	return name, 0, logi
}

func getBaseClockMhz() float64 {
	// "~MHz" 是当前频率（动态），仅作基频 fallback；有效频率以 PDH "Processor Frequency" 为准
	if k, err := registry.OpenKey(registry.LOCAL_MACHINE,
		`HARDWARE\DESCRIPTION\System\CentralProcessor\0`, registry.QUERY_VALUE); err == nil {
		defer k.Close()
		if v, _, err := k.GetIntegerValue("~MHz"); err == nil && v > 100 {
			return float64(v)
		}
	}
	return 0
}

func uptimeSeconds() *float64 {
	r, _, _ := procGetTickCount64.Call()
	s := math.Round(float64(r)/1000*10) / 10
	return &s
}

// ---------- 网络 ----------

func netSnapshot(q *pdhQuery) *Network {
	down := q.readArray(`\Network Interface(*)\Bytes Received/sec`)
	up := q.readArray(`\Network Interface(*)\Bytes Sent/sec`)
	if down == nil && up == nil {
		return nil
	}

	type agg struct {
		name string
		d, u float64
	}
	var list []agg
	names := map[string]bool{}
	for n := range down {
		names[n] = true
	}
	for n := range up {
		names[n] = true
	}
	for n := range names {
		a := agg{name: n}
		if v, ok := down[n]; ok && !isNan(v) && v >= 0 {
			a.d = v
		}
		if v, ok := up[n]; ok && !isNan(v) && v >= 0 {
			a.u = v
		}
		list = append(list, a)
	}
	sort.Slice(list, func(i, j int) bool { return list[i].name < list[j].name })

	// 活动网卡 = 流量最大者；desc 用 GetAdaptersAddresses 的真实描述匹配
	adapterByNorm := map[string]winAdapter{}
	for _, a := range enumAdapters() {
		if n := normName(a.desc); n != "" {
			adapterByNorm[n] = a
		}
		if n := normName(a.name); n != "" {
			if _, ok := adapterByNorm[n]; !ok {
				adapterByNorm[n] = a
			}
		}
	}

	out := &Network{Ifaces: []*NetIface{}}
	activeIdx := -1
	best := 0.0
	for i, a := range list {
		if a.d+a.u > best && !isLoopbackIface(a.name) {
			best = a.d + a.u
			activeIdx = i
		}
	}
	for i, a := range list {
		// 过滤零流量噪声（MBIM 虚拟实例等），只保留有流量或活动网卡
		if i != activeIdx && a.d+a.u < 1 {
			continue
		}
		display := unescapePdhName(a.name)
		ni := &NetIface{Name: display}
		if ad, ok := adapterByNorm[normName(a.name)]; ok && ad.desc != "" {
			d := ad.desc
			ni.Desc = &d
		} else {
			d := display
			ni.Desc = &d
		}
		dv := a.d
		uv := a.u
		ni.DownBps, ni.UpBps = &dv, &uv
		ni.Active = i == activeIdx
		out.Ifaces = append(out.Ifaces, ni)
	}
	if activeIdx >= 0 {
		a := list[activeIdx]
		d, u := a.d, a.u
		nm := unescapePdhName(a.name)
		if ad, ok := adapterByNorm[normName(a.name)]; ok && ad.desc != "" {
			nm = ad.desc
		}
		out.DownloadBps, out.UploadBps, out.Active = &d, &u, &nm
		for _, ni := range out.Ifaces {
			if ni.Active && ni.Desc != nil {
				out.ActiveDesc = ni.Desc
			}
		}
	}
	return out
}

// unescapePdhName — PDH 实例名会转义括号："Intel[R]" 实为 "Intel("。
func unescapePdhName(s string) string {
	s = strings.ReplaceAll(s, "[R]", "(")
	s = strings.ReplaceAll(s, "[P]", ")")
	return s
}

func isLoopbackIface(name string) bool {
	ln := strings.ToLower(name)
	return strings.Contains(ln, "loopback") || strings.Contains(ln, "loop")
}

// normName 规范化接口名用于匹配：小写、去空格与特殊字符
func normName(s string) string {
	var b strings.Builder
	for _, r := range strings.ToLower(s) {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') {
			b.WriteRune(r)
		}
	}
	return b.String()
}

// primaryLANIP 返回主局域网 IPv4：基于 GetAdaptersAddresses（up+网关+物理网卡优先）
func primaryLANIP() *string {
	a := pickPrimaryAdapter()
	if a == nil || len(a.ipv4) == 0 {
		return nil
	}
	return &a.ipv4[0]
}
