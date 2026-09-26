package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/gorilla/websocket"
)

func main() {
	base := "http://127.0.0.1:42710"

	// 1. hello
	hello, _ := http.Get(base + "/api/hello")
	hb, _ := io.ReadAll(hello.Body)
	fmt.Println("1) hello:", string(hb))

	// 2. 未鉴权 snapshot → 401
	r1, _ := http.Get(base + "/api/snapshot")
	fmt.Println("2) no-token snapshot:", r1.StatusCode)

	// 3. 错误 PIN 配对 → 403
	r2, _ := http.Post(base+"/api/pair", "application/json", strings.NewReader(`{"pin":"000000"}`))
	fmt.Println("3) wrong pin:", r2.StatusCode)

	// 4. 正确 PIN 配对
	cfgPath := os.Args[1]
	cfgData, _ := os.ReadFile(cfgPath)
	var cfg map[string]any
	json.Unmarshal(cfgData, &cfg)
	pin := cfg["devicePin"].(string)
	r3, _ := http.Post(base+"/api/pair", "application/json", strings.NewReader(`{"pin":"`+pin+`"}`))
	pb, _ := io.ReadAll(r3.Body)
	fmt.Println("4) pair:", r3.StatusCode, string(pb))
	var pair map[string]any
	json.Unmarshal(pb, &pair)
	token := pair["token"].(string)

	// 5. snapshot
	r4, _ := http.Get(base + "/api/snapshot?token=" + token)
	var snap map[string]any
	json.NewDecoder(r4.Body).Decode(&snap)
	cpu, _ := snap["cpu"].(map[string]any)
	fmt.Printf("5) snapshot: cpu.usage=%v gpus=%d mem.usage=%v\n",
		cpu["usage"], len(snap["gpus"].([]any)), snap["mem"].(map[string]any)["usagePct"])

	// 6. WebSocket 3 条消息
	wsURL := "ws://127.0.0.1:42710/api/ws?token=" + token
	ws, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		fmt.Println("6) ws dial err:", err)
		return
	}
	defer ws.Close()
	start := time.Now()
	var lastTS int64
	intervals := []float64{}
	for i := 0; i < 4; i++ {
		_, msg, err := ws.ReadMessage()
		if err != nil {
			fmt.Println("6) ws read err:", err)
			return
		}
		var m map[string]any
		json.Unmarshal(msg, &m)
		ts := int64(m["ts"].(float64))
		if i > 0 && lastTS > 0 {
			intervals = append(intervals, float64(ts-lastTS))
		}
		lastTS = ts
		if i == 0 {
			fmt.Println("6) ws msg0 bytes:", len(msg))
		}
	}
	fmt.Printf("7) ws intervals ms: %v (elapsed %v)\n", intervals, time.Since(start).Round(time.Millisecond))

	// 8. WS 降频控制消息
	ws.WriteJSON(map[string]any{"ctrl": map[string]any{"intervalMs": 2000}})
	_, msg, _ := ws.ReadMessage()
	var m2 map[string]any
	json.Unmarshal(msg, &m2)
	fmt.Println("8) after ctrl, next ts delta will show in next run")

	// 9. UDP 发现
	pc, _ := net.Dial("udp", "127.0.0.1:42711")
	pc.Write([]byte("PULSE:DISCOVER:v1"))
	pc.SetReadDeadline(time.Now().Add(3 * time.Second))
	buf := make([]byte, 1024)
	n, err := pc.Read(buf)
	if err != nil {
		fmt.Println("9) udp discovery err:", err)
	} else {
		fmt.Println("9) udp discovery:", string(buf[:n]))
	}
}
