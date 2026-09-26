package main

// winnet.go — GetAdaptersAddresses 薄封装：拿友好名/描述/网关/IPv4，
// 用于「当前网卡」判定与 IP 选择（PDH 实例名转义不可靠，只做匹配键）。

import (
	"net"
	"strings"
	"unsafe"

	"golang.org/x/sys/windows"
)

var procGetAdaptersAddresses = windows.NewLazySystemDLL("iphlpapi.dll").NewProc("GetAdaptersAddresses")

const (
	gaaSkipAnycast     = 0x0002
	gaaSkipMulticast   = 0x0004
	gaaSkipDnsServer   = 0x0008
	gaaIncludeGateways = 0x0040
	afUnspec           = 0
	ifOperUp           = 1
)

type winAdapter struct {
	name    string // FriendlyName，如 "WLAN"
	desc    string // 描述，如 "Intel(R) Wi-Fi 6E AX211 160MHz"
	up      bool
	hasGW   bool
	ipv4    []string
	macZero bool
}

// x64 字段偏移（IP_ADAPTER_ADDRESSES_LH 前段）
const (
	offNext      = 8
	offDesc      = 64
	offFriendly  = 72
	offPhysAddr  = 80
	offPhysLen   = 88
	offOperStatus = 104
	offUnicast   = 24
	offGateway   = 208
)

func enumAdapters() []winAdapter {
	var out []winAdapter
	size := uint32(32 * 1024)
	buf := make([]byte, size)
	r, _, _ := procGetAdaptersAddresses.Call(afUnspec, gaaSkipAnycast|gaaSkipMulticast|gaaSkipDnsServer|gaaIncludeGateways,
		0, uintptr(unsafe.Pointer(&buf[0])), uintptr(unsafe.Pointer(&size)))
	if r == 0x6F /*ERROR_BUFFER_OVERFLOW*/ {
		if size > 4<<20 {
			return nil
		}
		buf = make([]byte, size)
		r, _, _ = procGetAdaptersAddresses.Call(afUnspec, gaaSkipAnycast|gaaSkipMulticast|gaaSkipDnsServer|gaaIncludeGateways,
			0, uintptr(unsafe.Pointer(&buf[0])), uintptr(unsafe.Pointer(&size)))
	}
	if r != 0 {
		return nil
	}
	node := unsafe.Pointer(&buf[0])
	for i := 0; i < 64 && node != nil; i++ {
		next := rdPtr(node, offNext)
		desc := utf16PtrToString(unsafe.Pointer(rdPtr(node, offDesc)))
		friendly := utf16PtrToString(unsafe.Pointer(rdPtr(node, offFriendly)))
		up := rdU32(node, offOperStatus) == ifOperUp

		a := winAdapter{name: friendly, desc: desc, up: up}
		if rdU32(node, offPhysLen) == 0 {
			a.macZero = true
		}

		// 单播地址链表：{Alignment 8, Next 8, SOCKET_ADDRESS {ptr 8, len 4+pad}}
		ua := unsafe.Pointer(rdPtr(node, offUnicast))
		for j := 0; j < 16 && ua != nil; j++ {
			sa := unsafe.Pointer(rdPtr(ua, 16))
			saLen := rdU32(ua, 24)
			if sa != nil && saLen >= 16 && rdU16(sa, 0) == 2 /*AF_INET*/ {
				v := rdU32(sa, 4)
				ip := net.IPv4(byte(v), byte(v>>8), byte(v>>16), byte(v>>24))
				a.ipv4 = append(a.ipv4, ip.String())
			}
			ua = unsafe.Pointer(rdPtr(ua, 8))
		}
		// 网关链表（偏移同构）
		ga := unsafe.Pointer(rdPtr(node, offGateway))
		for j := 0; j < 8 && ga != nil; j++ {
			sa := unsafe.Pointer(rdPtr(ga, 16))
			if sa != nil && rdU16(sa, 0) == 2 {
				a.hasGW = true
			}
			ga = unsafe.Pointer(rdPtr(ga, 8))
		}
		out = append(out, a)
		node = unsafe.Pointer(next)
	}
	return out
}

// 读取非 Go 内存（GAA 缓冲区）字段：unsafe.Add 保证 GC 安全的表达式内转换
func rdPtr(base unsafe.Pointer, off uintptr) uintptr {
	return *(*uintptr)(unsafe.Add(base, off))
}
func rdU32(base unsafe.Pointer, off uintptr) uint32 { return *(*uint32)(unsafe.Add(base, off)) }
func rdU16(base unsafe.Pointer, off uintptr) uint16 { return *(*uint16)(unsafe.Add(base, off)) }

func utf16PtrToString(p unsafe.Pointer) string {
	if p == nil {
		return ""
	}
	var chars []uint16
	for i := 0; i < 512; i++ {
		v := *(*uint16)(unsafe.Add(p, uintptr(i)*2))
		if v == 0 {
			break
		}
		chars = append(chars, v)
	}
	return windows.UTF16ToString(chars)
}

// pickPrimaryAdapter 选主网卡：up + 有网关 + 私网 IPv4 优先，物理网卡名加分
func pickPrimaryAdapter() *winAdapter {
	ads := enumAdapters()
	var best *winAdapter
	bestScore := -100
	for i := range ads {
		a := &ads[i]
		if !a.up || len(a.ipv4) == 0 {
			continue
		}
		score := 0
		lname := strings.ToLower(a.name + " " + a.desc)
		for _, h := range []string{"virtual", "tunnel", "wintun", "tap", "vpn", "ppp", "loop", "vmware",
			"virtualbox", "hyper-v", "vethernet", "wsl", "docker", "bluetooth", "vgate", "mobile broadband"} {
			if strings.Contains(lname, h) {
				score -= 10
				break
			}
		}
		for _, h := range []string{"wlan", "wi-fi", "wifi", "wireless", "以太网", "ethernet", "local area"} {
			if strings.Contains(lname, h) {
				score += 5
				break
			}
		}
		if a.hasGW {
			score += 6
		}
		if a.macZero {
			score -= 3
		}
		if score > bestScore {
			bestScore = score
			best = a
		}
	}
	return best
}
