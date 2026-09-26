package main

// pdh.go — PDH (Performance Data Helper) 薄封装：一条 query 挂多个计数器，
// 每 tick 一次 CollectQueryData，支持单值与通配实例数组两种读取。
// 结构体布局严格对齐 x64，init 时自校验。

import (
	"fmt"
	"math"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	pdh                        = windows.NewLazySystemDLL("pdh.dll")
	procPdhOpenQueryW          = pdh.NewProc("PdhOpenQueryW")
	procPdhAddEnglishCounterW  = pdh.NewProc("PdhAddEnglishCounterW")
	procPdhCollectQueryData    = pdh.NewProc("PdhCollectQueryData")
	procPdhGetFormattedCounter = pdh.NewProc("PdhGetFormattedCounterValue") // 无字符串参数，无 W 后缀
	procPdhGetFormattedArray   = pdh.NewProc("PdhGetFormattedCounterArrayW")
	procPdhCloseQuery          = pdh.NewProc("PdhCloseQuery")
)

const (
	pdhFmtDouble   = 0x00000200
	pdhMoreData    = 0x800007D2
	pdhNoData      = 0x800007D5
	pdhInvalidData = 0xC0000BC0
	pdhMaxName     = 209 // PDH_MAX_COUNTER_NAME
)

// PDH_FMT_COUNTERVALUE_ITEM_W（x64, 实测 stride=24）:
// LPWSTR szName(8B，指向缓冲区尾部的名字串) + PDH_FMT_COUNTERVALUE{CStatus u32 + pad + union double}
type pdhCounterItem struct {
	NamePtr uintptr
	CStatus uint32
	_       [4]byte
	Value   float64
}

// PDH_FMT_COUNTERVALUE（x64, size=16）
type pdhCounterValue struct {
	CStatus uint32
	_       [4]byte
	Value   float64
}

func init() {
	if unsafe.Sizeof(pdhCounterItem{}) != 24 || unsafe.Sizeof(pdhCounterValue{}) != 16 {
		panic("PDH struct layout mismatch")
	}
}

type pdhQuery struct {
	h        windows.Handle
	counters map[string]windows.Handle
}

func openPdhQuery() (*pdhQuery, error) {
	var h windows.Handle
	r, _, _ := procPdhOpenQueryW.Call(0, 0, uintptr(unsafe.Pointer(&h)))
	if r != 0 {
		return nil, fmt.Errorf("PdhOpenQuery: 0x%x", r)
	}
	return &pdhQuery{h: h, counters: map[string]windows.Handle{}}, nil
}

// add 注册英文计数器路径（PdhAddEnglishCounterW 保证跨语言可用）；失败仅记录，不致命。
func (q *pdhQuery) add(path string) {
	if _, ok := q.counters[path]; ok {
		return
	}
	var h windows.Handle
	p16, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return
	}
	r, _, _ := procPdhAddEnglishCounterW.Call(uintptr(q.h), uintptr(unsafe.Pointer(p16)), 0, uintptr(unsafe.Pointer(&h)))
	if r != 0 {
		dbg("pdh add failed: %s -> 0x%x", path, r)
		return
	}
	q.counters[path] = h
}

func (q *pdhQuery) collect() error {
	r, _, _ := procPdhCollectQueryData.Call(uintptr(q.h))
	if r != 0 {
		return fmt.Errorf("PdhCollectQueryData: 0x%x", r)
	}
	return nil
}

func (q *pdhQuery) close() {
	if q.h != 0 {
		procPdhCloseQuery.Call(uintptr(q.h))
		q.h = 0
	}
}

// readSingle 读取单值计数器；失败或 CStatus 非零返回 NaN。
func (q *pdhQuery) readSingle(path string) float64 {
	h, ok := q.counters[path]
	if !ok {
		return nan()
	}
	var v pdhCounterValue
	r, _, _ := procPdhGetFormattedCounter.Call(uintptr(h), pdhFmtDouble, 0, uintptr(unsafe.Pointer(&v)))
	if r != 0 {
		return nan()
	}
	if v.CStatus != 0 {
		return nan()
	}
	return v.Value
}

// readArray 读取通配实例计数器 → {实例名: 值}；失败返回 nil。
func (q *pdhQuery) readArray(path string) map[string]float64 {
	h, ok := q.counters[path]
	if !ok {
		return nil
	}
	size := uint32(64 * 1024)
	for tries := 0; tries < 4; tries++ {
		buf := make([]byte, size)
		var itemCnt uint32
		r, _, _ := procPdhGetFormattedArray.Call(uintptr(h), pdhFmtDouble,
			uintptr(unsafe.Pointer(&size)), uintptr(unsafe.Pointer(&itemCnt)), uintptr(unsafe.Pointer(&buf[0])))
		if r == pdhMoreData {
			size *= 2
			continue
		}
		if r != 0 || itemCnt == 0 {
			return nil
		}
		itemSize := int(unsafe.Sizeof(pdhCounterItem{}))
		if int(itemCnt)*itemSize > len(buf) {
			return nil
		}
		base := uintptr(unsafe.Pointer(&buf[0]))
		out := make(map[string]float64, itemCnt)
		for i := 0; i < int(itemCnt); i++ {
			it := (*pdhCounterItem)(unsafe.Pointer(&buf[i*itemSize]))
			if it.CStatus != 0 || it.NamePtr == 0 {
				continue
			}
			off := int(it.NamePtr - base)
			if off < 0 || off >= len(buf)-2 {
				continue
			}
			name := utf16ToStringZ(buf[off:])
			if name == "" {
				continue
			}
			out[name] = it.Value
		}
		return out
	}
	return nil
}

// utf16ToStringZ 从字节缓冲区读取以 NUL 结尾的 UTF-16 串
func utf16ToStringZ(b []byte) string {
	var sb []uint16
	for i := 0; i+1 < len(b); i += 2 {
		v := uint16(b[i]) | uint16(b[i+1])<<8
		if v == 0 {
			break
		}
		sb = append(sb, v)
	}
	return windows.UTF16ToString(sb)
}

func nan() float64    { return math.NaN() }
func isNan(v float64) bool { return math.IsNaN(v) }
