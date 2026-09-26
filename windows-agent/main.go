package main

// main.go — Pulse LAN Monitor Agent 入口。
// 默认以 windowsgui 子系统编译（无控制台窗口）；加 -console 跑前台调试。

import (
	"context"
	"runtime/debug"
	"flag"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"
)

func main() {
	console := flag.Bool("console", false, "前台模式（保留控制台输出）")
	port := flag.Int("port", 0, "覆盖 HTTP 端口")
	flag.Parse()

	if *console {
		debugMode = true
	}

	cfg := loadConfig()
	if *port > 0 {
		cfg.Port = *port
	}

	// 低占用调优：更激进的 GC 目标 + 周期归还内存给 OS
	debug.SetGCPercent(50)
	go func() {
		t := time.NewTicker(5 * time.Minute)
		defer t.Stop()
		for range t.C {
			debug.FreeOSMemory()
		}
	}()

	logDirPath := filepath.Join(defaultCfgDir(), "logs")
	initLog(logDirPath, *console)
	logf("Pulse Agent %s starting (port=%d discovery=%d interval=%dms)",
		agentVersion, cfg.Port, cfg.DiscoveryPort, cfg.IntervalMs)

	collector, err := NewCollector(time.Duration(cfg.IntervalMs) * time.Millisecond)
	if err != nil {
		logf("collector init failed: %v", err)
		os.Exit(1)
	}
	theCfg = cfg
	theCollector = collector

	stop := make(chan struct{})
	go collector.Run(stop)
	go NewDiscovery(cfg, collector).Run(stop)

	srv := &http.Server{
		Addr:              net.JoinHostPort(cfg.BindIP, itoa(cfg.Port)),
		Handler:           NewServer(cfg, collector).handler(),
		ReadHeaderTimeout: 5 * time.Second,
	}

	// 刷新 status.json（供 start.bat 显示与 PIN 查看）：等首帧快照写一次，之后 IP 变化才重写
	go func() {
		lastIP := ""
		wrote := false
		for i := 0; i < 10; i++ { // 首帧最多等 10s
			if s := collector.Snapshot(); s != nil && s.Sys != nil && s.Sys.IP != nil {
				lastIP = *s.Sys.IP
				writeStatus(cfg, s.Sys.Host, lastIP)
				wrote = true
				break
			}
			time.Sleep(time.Second)
		}
		if !wrote {
			writeStatus(cfg, "", "")
		}
		t := time.NewTicker(60 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-stop:
				return
			case <-t.C:
				if s := collector.Snapshot(); s != nil && s.Sys != nil && s.Sys.IP != nil && *s.Sys.IP != lastIP {
					lastIP = *s.Sys.IP
					writeStatus(cfg, s.Sys.Host, lastIP)
				}
			}
		}
	}()

	go func() {
		sig := make(chan os.Signal, 1)
		signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
		<-sig
		close(stop)
		_ = srv.Shutdown(context.Background())
	}()

	logf("http listening on %s", srv.Addr)
	if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		logf("http server error: %v", err)
		os.Exit(1)
	}
	logf("stopped")
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	neg := n < 0
	if neg {
		n = -n
	}
	var b [8]byte
	i := len(b)
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		b[i] = '-'
	}
	return string(b[i:])
}
