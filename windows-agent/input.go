package main

// input.go — 远程鼠标/键盘注入：user32 SendInput（用户态，免管理员、免驱动）。
// 仅作用于本会话桌面；UAC 安全桌面收不到输入属预期。

import (
	"encoding/json"
	"math"
	"sync/atomic"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	user32            = windows.NewLazySystemDLL("user32.dll")
	procSendInput     = user32.NewProc("SendInput")
	procMapVirtualKey = user32.NewProc("MapVirtualKeyW")
)

const (
	inputMouse    = 0
	inputKeyboard = 1

	meMove       = 0x0001
	meLeftDown   = 0x0002
	meLeftUp     = 0x0004
	meRightDown  = 0x0008
	meRightUp    = 0x0010
	meMiddleDown = 0x0020
	meMiddleUp   = 0x0040
	meWheel      = 0x0800
	meHWheel     = 0x1000

	kfKeyUp     = 0x0002
	kfUnicode   = 0x0004
	mapvkToVsc  = 0
)

// INPUT x64：type(4)+pad(4)+union(32) = 40 字节
type inputPacket struct {
	typ uint32
	_   uint32
	u   [32]byte
}

type mouseInput struct {
	dx, dy int32
	data   uint32
	flags  uint32
	time   uint32
	_      uint32
	extra  uintptr
}

type keybdInput struct {
	vk, scan uint16
	flags    uint32
	time     uint32
	_        uint32
	extra    uintptr
}

func mousePacket(dx, dy int32, data, flags uint32) inputPacket {
	mi := mouseInput{dx: dx, dy: dy, data: data, flags: flags}
	p := inputPacket{typ: inputMouse}
	p.u = *(*[32]byte)(unsafe.Pointer(&mi))
	return p
}

func keyPacket(vk, scan uint16, flags uint32) inputPacket {
	ki := keybdInput{vk: vk, scan: scan, flags: flags}
	p := inputPacket{typ: inputKeyboard}
	clear(p.u[:])
	copy(p.u[:], (*[24]byte)(unsafe.Pointer(&ki))[:])
	return p
}

func sendInputs(ins []inputPacket) bool {
	if len(ins) == 0 {
		return true
	}
	r, _, _ := procSendInput.Call(uintptr(len(ins)), uintptr(unsafe.Pointer(&ins[0])), unsafe.Sizeof(inputPacket{}))
	return r == uintptr(len(ins))
}

// ---- 语义动作 ----

func inputMouseMove(dx, dy float64) {
	sendInputs([]inputPacket{mousePacket(int32(math.Round(dx)), int32(math.Round(dy)), 0, meMove)})
}

func inputButton(button, down int) {
	var f uint32
	switch button {
	case 1:
		f = meRightDown
		if down == 0 {
			f = meRightUp
		}
	case 2:
		f = meMiddleDown
		if down == 0 {
			f = meMiddleUp
		}
	default:
		f = meLeftDown
		if down == 0 {
			f = meLeftUp
		}
	}
	sendInputs([]inputPacket{mousePacket(0, 0, 0, f)})
}

func inputClick(button int, dbl bool) {
	inputButton(button, 1)
	inputButton(button, 0)
	if dbl {
		inputButton(button, 1)
		inputButton(button, 0)
	}
}

func inputWheel(delta float64) {
	d := int32(math.Round(delta))
	if d == 0 {
		return
	}
	sendInputs([]inputPacket{mousePacket(0, 0, uint32(d), meWheel)})
}

func inputHWheel(delta float64) {
	d := int32(math.Round(delta))
	if d == 0 {
		return
	}
	sendInputs([]inputPacket{mousePacket(0, 0, uint32(d), meHWheel)})
}

var vkMap = map[string]uint16{
	"enter": 0x0D, "backspace": 0x08, "delete": 0x2E, "del": 0x2E, "esc": 0x1B,
	"tab": 0x09, "space": 0x20, "left": 0x25, "up": 0x26, "right": 0x27, "down": 0x28,
	"home": 0x24, "end": 0x23, "pageup": 0x21, "pagedown": 0x22, "pgup": 0x21, "pgdn": 0x22,
	"ctrl": 0x11, "alt": 0x12, "shift": 0x10, "win": 0x5B, "apps": 0x5D,
	"insert": 0x2D, "ins": 0x2D, "printscreen": 0x2C,
}
var letterVK = func() map[string]uint16 {
	m := map[string]uint16{}
	for c := byte('a'); c <= 'z'; c++ {
		m[string(c)] = uint16(c - 'a' + 0x41)
	}
	for c := byte('0'); c <= '9'; c++ {
		m[string(c)] = uint16(c - '0' + 0x30)
	}
	return m
}()

func lookupVK(name string) (uint16, bool) {
	n := len(name)
	if n == 2 && (name[0] == 'f' || name[0] == 'F') && name[1] >= '1' && name[1] <= '9' {
		return 0x70 + uint16(name[1]-'1'), true
	}
	if n == 3 && (name[0] == 'f' || name[0] == 'F') && name[1] == '1' && name[2] >= '0' && name[2] <= '2' {
		return 0x70 + 9 + uint16(name[2]-'0'), true
	}
	if v, ok := vkMap[name]; ok {
		return v, true
	}
	if v, ok := letterVK[name]; ok {
		return v, true
	}
	return 0, false
}

func keyScan(vk uint16) uint16 {
	r, _, _ := procMapVirtualKey.Call(uintptr(vk), mapvkToVsc)
	return uint16(r)
}

// inputKeyTap 单键按下抬起
func inputKeyTap(name string) bool {
	vk, ok := lookupVK(name)
	if !ok {
		return false
	}
	sc := keyScan(vk)
	return sendInputs([]inputPacket{keyPacket(vk, sc, 0), keyPacket(vk, sc, kfKeyUp)})
}

// inputCombo 依序按下、逆序抬起
func inputCombo(names []string) bool {
	var down, up []inputPacket
	for _, n := range names {
		vk, ok := lookupVK(n)
		if !ok {
			continue
		}
		sc := keyScan(vk)
		down = append(down, keyPacket(vk, sc, 0))
	}
	for i := len(down) - 1; i >= 0; i-- {
		p := down[i]
		ki := (*keybdInput)(unsafe.Pointer(&p.u[0]))
		ki.flags = kfKeyUp
		up = append(up, p)
	}
	all := append(down, up...)
	return sendInputs(all)
}

// inputText Unicode 文本注入（支持中文）：逐 UTF-16 码元 down+up
func inputText(s string) bool {
	units := []uint16{}
	for _, r := range s {
		if r <= 0xFFFF && !(r >= 0xD800 && r <= 0xDFFF) {
			units = append(units, uint16(r))
		} else {
			r -= 0x10000
			units = append(units, uint16(0xD800+(r>>10)), uint16(0xDC00+(r&0x3FF)))
		}
	}
	var ins []inputPacket
	for _, u := range units {
		ins = append(ins, keyPacket(0, u, kfUnicode), keyPacket(0, u, kfUnicode|kfKeyUp))
	}
	return sendInputs(ins)
}

// ---- WS 消息 ----

type inputMsg struct {
	T    string   `json:"t"`
	DX   float64  `json:"dx"`
	DY   float64  `json:"dy"`
	B    int      `json:"b"`
	D    int      `json:"d"`
	Dbl  int      `json:"dbl"`
	Delta float64 `json:"delta"`
	S    string   `json:"s"`
	K    string   `json:"k"`
	Keys []string `json:"keys"`
}

var inputEventCount uint64

func applyInput(payload []byte) {
	var m inputMsg
	if err := json.Unmarshal(payload, &m); err != nil {
		return
	}
	atomic.AddUint64(&inputEventCount, 1)
	switch m.T {
	case "mv":
		inputMouseMove(m.DX, m.DY)
	case "cl":
		inputClick(m.B, m.Dbl == 1)
	case "bd":
		inputButton(m.B, m.D)
	case "wh":
		inputWheel(m.Delta)
	case "hw":
		inputHWheel(m.Delta)
	case "tx":
		if m.S != "" {
			inputText(m.S)
		}
	case "kk":
		inputKeyTap(m.K)
	case "cb":
		inputCombo(m.Keys)
	}
}
