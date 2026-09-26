package main

// screen.go — 屏幕镜像：GDI BitBlt 抓屏 → JPEG。用户态，免管理员。
// SetProcessDPIAware 保证高 DPI 下抓到物理分辨率。

import (
	"bytes"
	"image"
	"image/jpeg"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	gdi32                  = windows.NewLazySystemDLL("gdi32.dll")
	pGetSystemMetrics      = user32.NewProc("GetSystemMetrics")
	pSetProcessDPIAware    = user32.NewProc("SetProcessDPIAware")
	pGetDC                 = user32.NewProc("GetDC")
	pReleaseDC             = user32.NewProc("ReleaseDC")
	pCreateCompatibleDC    = gdi32.NewProc("CreateCompatibleDC")
	pCreateCompatibleBmp   = gdi32.NewProc("CreateCompatibleBitmap")
	pSelectObject          = gdi32.NewProc("SelectObject")
	pBitBlt                = gdi32.NewProc("BitBlt")
	pGetDIBits             = gdi32.NewProc("GetDIBits")
	pGetCursorInfo         = user32.NewProc("GetCursorInfo")
	pGetIconInfo           = user32.NewProc("GetIconInfo")
	pDrawIconEx            = user32.NewProc("DrawIconEx")
	pDeleteObject          = gdi32.NewProc("DeleteObject")
	pDeleteDC              = gdi32.NewProc("DeleteDC")
)

const (
	smCXScreen = 0
	smCYScreen = 1
	srccopy    = 0x00CC0020
	dibRGB     = 0
	cursorShowing = 1
	diNormal   = 3
)

type pointStruct struct{ X, Y int32 }

type cursorInfoStruct struct {
	CbSize uint32
	Flags  uint32
	Cursor uintptr
	Pos    pointStruct
}

type iconInfoStruct struct {
	FIcon    int32
	XHotspot uint32
	YHotspot uint32
	MaskBmp  uintptr
	ColorBmp uintptr
}

type bmiHeader struct {
	Size          uint32
	W, H          int32
	Planes        uint16
	BitCount      uint16
	Compression   uint32
	SizeImage     uint32
	XPpm, YPpm    uint32
	ClrUsed       uint32
	ClrImportant  uint32
}

var (
	screenMu     sync.Mutex
	screenCache  []byte
	screenCacheAt int64
	dpiDone      uint32
)

// captureScreenJPEG 抓取主屏并编码 JPEG（80ms 内命中缓存）
func captureScreenJPEG(quality int) ([]byte, error) {
	if atomic.CompareAndSwapUint32(&dpiDone, 0, 1) {
		pSetProcessDPIAware.Call()
	}
	screenMu.Lock()
	defer screenMu.Unlock()
	now := time.Now().UnixMilli()
	if screenCache != nil && now-screenCacheAt < 80 {
		return screenCache, nil
	}

	w, _, _ := pGetSystemMetrics.Call(smCXScreen)
	h, _, _ := pGetSystemMetrics.Call(smCYScreen)
	if w == 0 || h == 0 {
		return nil, errStr("GetSystemMetrics failed")
	}
	screenDC, _, _ := pGetDC.Call(0)
	if screenDC == 0 {
		return nil, errStr("GetDC failed")
	}
	defer pReleaseDC.Call(0, screenDC)
	memDC, _, _ := pCreateCompatibleDC.Call(screenDC)
	if memDC == 0 {
		return nil, errStr("CreateCompatibleDC failed")
	}
	defer pDeleteDC.Call(memDC)
	bmp, _, _ := pCreateCompatibleBmp.Call(screenDC, w, h)
	if bmp == 0 {
		return nil, errStr("CreateCompatibleBitmap failed")
	}
	defer pDeleteObject.Call(bmp)
	prev, _, _ := pSelectObject.Call(memDC, bmp)
	defer pSelectObject.Call(memDC, prev)
	if r, _, _ := pBitBlt.Call(memDC, 0, 0, w, h, screenDC, 0, 0, srccopy); r == 0 {
		return nil, errStr("BitBlt failed")
	}

	// BitBlt 抓不到硬件光标叠加层：把当前鼠标指针画进帧里（远程查看必需）
	var ci cursorInfoStruct
	ci.CbSize = uint32(unsafe.Sizeof(ci))
	if r, _, _ := pGetCursorInfo.Call(uintptr(unsafe.Pointer(&ci))); r != 0 &&
		ci.Flags&cursorShowing != 0 && ci.Cursor != 0 {
		var ii iconInfoStruct
		if r2, _, _ := pGetIconInfo.Call(ci.Cursor, uintptr(unsafe.Pointer(&ii))); r2 != 0 {
			pDrawIconEx.Call(memDC,
				uintptr(int32(ci.Pos.X)-int32(ii.XHotspot)),
				uintptr(int32(ci.Pos.Y)-int32(ii.YHotspot)),
				ci.Cursor, 0, 0, 0, 0, diNormal)
			// GetIconInfo 返回的位图由调用方释放
			if ii.MaskBmp != 0 {
				pDeleteObject.Call(ii.MaskBmp)
			}
			if ii.ColorBmp != 0 {
				pDeleteObject.Call(ii.ColorBmp)
			}
		}
	}

	hdr := bmiHeader{Size: 40, W: int32(int32(w)), H: -int32(int32(h)), Planes: 1, BitCount: 32}
	buf := make([]byte, int(w)*int(h)*4)
	if r, _, _ := pGetDIBits.Call(memDC, bmp, 0, h, uintptr(unsafe.Pointer(&buf[0])),
		uintptr(unsafe.Pointer(&hdr)), dibRGB); r == 0 {
		return nil, errStr("GetDIBits failed")
	}
	// BGRA → RGBA + 不透明 alpha
	for i := 0; i+3 < len(buf); i += 4 {
		buf[i], buf[i+2] = buf[i+2], buf[i]
		buf[i+3] = 255
	}
	img := &image.RGBA{Pix: buf, Stride: int(w) * 4, Rect: image.Rect(0, 0, int(w), int(h))}
	var out bytes.Buffer
	if err := jpeg.Encode(&out, img, &jpeg.Options{Quality: quality}); err != nil {
		return nil, err
	}
	screenCache, screenCacheAt = out.Bytes(), now
	return screenCache, nil
}

type strErr string

func (e strErr) Error() string { return string(e) }
func errStr(s string) error    { return strErr(s) }
