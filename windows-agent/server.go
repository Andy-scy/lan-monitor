package main

// server.go — HTTP + WebSocket 服务：hello / pair / snapshot / ws，Bearer Token 鉴权，配对限速。

import (
	"crypto/subtle"
	"sync/atomic"
	"encoding/json"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// 最近一次有客户端活动的时间（unix ms）；空闲时采集器自动降频
var lastClientSeen atomic.Int64

// 全局句柄：Web 控制台与优化端点使用
var (
	theCfg       *Config
	theCollector *Collector
)

func theTokenOK(r *http.Request) bool {
	tok := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	if tok == "" {
		tok = r.URL.Query().Get("token")
	}
	return subtle.ConstantTimeCompare([]byte(tok), []byte(theCfg.Token)) == 1
}

type Server struct {
	cfg       *Config
	collector *Collector
	upgrader  websocket.Upgrader
	pairMu    sync.Mutex
	pairTries map[string][]time.Time // ip → 时间窗
}

func NewServer(cfg *Config, c *Collector) *Server {
	return &Server{cfg: cfg, collector: c,
		upgrader: websocket.Upgrader{ReadBufferSize: 1024, WriteBufferSize: 4096},
		pairTries: map[string][]time.Time{}}
}

func (s *Server) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/api/hello", s.handleHello)
	mux.HandleFunc("/api/pair", s.handlePair)
	mux.HandleFunc("/api/snapshot", s.auth(s.handleSnapshot))
	mux.HandleFunc("/api/ws", s.handleWS)
	mux.HandleFunc("/api/input", s.handleInputWS)
	mux.HandleFunc("/api/screen", s.auth(s.handleScreen))
	mux.HandleFunc("/api/optimize", s.auth(func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 200, runOptimize())
	}))
	mux.HandleFunc("/api/config", handleConfig)
	mux.HandleFunc("/api/pin/reset", handlePinReset)
	mux.HandleFunc("/api/local-token", handleLocalToken)
	mux.HandleFunc("/api/restart", handleRestart)
	mux.HandleFunc("/api/stop", handleStop)
	mux.HandleFunc("/", handleWeb)
	return mux
}

func (s *Server) auth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		tok := r.Header.Get("Authorization")
		tok = strings.TrimPrefix(tok, "Bearer ")
		if tok == "" {
			tok = r.URL.Query().Get("token")
		}
		if subtle.ConstantTimeCompare([]byte(tok), []byte(s.cfg.Token)) != 1 {
			w.Header().Set("Pulse-Error", "unauthorized")
			http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
			return
		}
		lastClientSeen.Store(time.Now().UnixMilli())
		next(w, r)
	}
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func (s *Server) handleHello(w http.ResponseWriter, r *http.Request) {
	snap := s.collector.Snapshot()
	h := Hello{Name: "Pulse Agent", Agent: agentVersion, Port: s.cfg.Port, ID: s.cfg.DeviceID}
	if snap != nil && snap.Sys != nil {
		h.Host = snap.Sys.Host
		h.OS = snap.Sys.OS
		if snap.Sys.IP != nil {
			h.PairingRequired = true
		}
	}
	h.PairingRequired = false // v1.4：LAN 内免 PIN
	writeJSON(w, 200, h)
}

func (s *Server) allowPair(ip string) bool {
	s.pairMu.Lock()
	defer s.pairMu.Unlock()
	now := time.Now()
	var keep []time.Time
	for _, t := range s.pairTries[ip] {
		if now.Sub(t) < time.Minute {
			keep = append(keep, t)
		}
	}
	if len(keep) >= 5 {
		s.pairTries[ip] = keep
		return false
	}
	s.pairTries[ip] = append(keep, now)
	return true
}

// handlePair — v1.4 起 LAN 内免 PIN：发现本服务的设备直接领取 Token
func (s *Server) handlePair(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, 405, map[string]string{"error": "method not allowed"})
		return
	}
	snap := s.collector.Snapshot()
	resp := map[string]string{"token": s.cfg.Token, "id": s.cfg.DeviceID}
	if snap != nil && snap.Sys != nil {
		resp["name"] = snap.Sys.Host
	}
	writeJSON(w, 200, resp)
}

func (s *Server) handleSnapshot(w http.ResponseWriter, r *http.Request) {
	snap := s.collector.Snapshot()
	if snap == nil {
		writeJSON(w, 503, map[string]string{"error": "warming up"})
		return
	}
	writeJSON(w, 200, snap)
}

// handleWS — 鉴权（query token）后按 tick 推送快照；客户端可发 {"ctrl":{"intervalMs":N}}
func (s *Server) handleWS(w http.ResponseWriter, r *http.Request) {
	tok := r.URL.Query().Get("token")
	if subtle.ConstantTimeCompare([]byte(tok), []byte(s.cfg.Token)) != 1 {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	conn, err := s.upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer conn.Close()
	interval := time.Duration(s.cfg.IntervalMs) * time.Millisecond
	_ = conn.SetReadDeadline(time.Now().Add(90 * time.Second))
	conn.SetPongHandler(func(string) error {
		_ = conn.SetReadDeadline(time.Now().Add(90 * time.Second))
		return nil
	})

	stop := make(chan struct{})
	done := make(chan struct{})
	go func() { // 读泵：控制消息 + pong 保活
		defer close(done)
		for {
			_, payload, err := conn.ReadMessage()
			if err != nil {
				return
			}
			_ = conn.SetReadDeadline(time.Now().Add(90 * time.Second))
			var msg struct {
				Ctrl *struct {
					IntervalMs int `json:"intervalMs"`
				} `json:"ctrl"`
			}
			if err := json.Unmarshal(payload, &msg); err == nil && msg.Ctrl != nil {
				ms := msg.Ctrl.IntervalMs
				if ms < 500 {
					ms = 500
				}
				if ms > 10000 {
					ms = 10000
				}
				interval = time.Duration(ms) * time.Millisecond
			}
		}
	}()
	defer close(stop)

	tick := time.NewTicker(interval)
	defer tick.Stop()
	snap := s.collector.Snapshot()
	if snap != nil {
		_ = conn.WriteJSON(snap)
	}
	for {
		select {
		case <-stop:
			return
		case <-done:
			return
		case <-tick.C:
			tick.Reset(interval)
			snap := s.collector.Snapshot()
			if snap == nil {
				continue
			}
			_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
			if err := conn.WriteJSON(snap); err != nil {
				return
			}
			lastClientSeen.Store(time.Now().UnixMilli())
		}
	}
}

	// fmt 保留给后续调试输出

// handleInputWS — 遥控通道：客户端高频小消息（鼠标移动/点击/滚轮/键盘），
// 逐条解析并 SendInput。无服务端下行（协议层 ping/pong 保活）。
func (s *Server) handleInputWS(w http.ResponseWriter, r *http.Request) {
	tok := r.URL.Query().Get("token")
	if subtle.ConstantTimeCompare([]byte(tok), []byte(s.cfg.Token)) != 1 {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	conn, err := s.upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer conn.Close()
	_ = conn.SetReadDeadline(time.Now().Add(60 * time.Second))
	conn.SetPingHandler(func(string) error {
		_ = conn.SetReadDeadline(time.Now().Add(60 * time.Second))
		return nil
	})
	for {
		_, payload, err := conn.ReadMessage()
		if err != nil {
			return
		}
		_ = conn.SetReadDeadline(time.Now().Add(60 * time.Second))
		applyInput(payload)
	}
}

// handleScreen — 屏幕镜像端点：返回当前主屏 JPEG
func (s *Server) handleScreen(w http.ResponseWriter, r *http.Request) {
	data, err := captureScreenJPEG(55)
	if err != nil {
		http.Error(w, `{"error":"capture failed"}`, http.StatusServiceUnavailable)
		return
	}
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(data)
}
