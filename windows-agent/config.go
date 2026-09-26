package main

// config.go — config.json（首启自动生成，缺失项自动补齐）+ status.json（给 start.bat 与配对 UI 用）。

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"time"
)

type Config struct {
	Port          int    `json:"port"`
	DiscoveryPort int    `json:"discoveryPort"`
	IntervalMs    int    `json:"intervalMs"`
	BindIP        string `json:"bindIP"`
	Token         string `json:"token"`
	DevicePin     string `json:"devicePin"`
	DeviceID      string `json:"deviceId"`
}

func defaultCfgDir() string { return exeDir() }

func loadConfig() *Config {
	dir := defaultCfgDir()
	path := filepath.Join(dir, "config.json")
	cfg := &Config{}
	if data, err := os.ReadFile(path); err == nil {
		_ = json.Unmarshal(data, cfg)
	}
	if cfg.Port <= 0 || cfg.Port > 65535 {
		cfg.Port = defaultPort
	}
	if cfg.DiscoveryPort <= 0 || cfg.DiscoveryPort > 65535 {
		cfg.DiscoveryPort = defaultDiscPort
	}
	if cfg.IntervalMs < 500 {
		cfg.IntervalMs = 1000
	}
	if cfg.IntervalMs > 10000 {
		cfg.IntervalMs = 10000
	}
	if len(cfg.Token) < 16 {
		cfg.Token = randHex(24)
	}
	if len(cfg.DeviceID) < 6 {
		cfg.DeviceID = randHex(6)
	}
	if len(cfg.DevicePin) != 6 {
		cfg.DevicePin = randPIN()
	}
	saveConfig(cfg)
	return cfg
}

func saveConfig(cfg *Config) {
	path := filepath.Join(defaultCfgDir(), "config.json")
	if data, err := json.MarshalIndent(cfg, "", "  "); err == nil {
		_ = os.WriteFile(path, data, 0o600)
	}
}

func randHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func randPIN() string {
	max := big.NewInt(1000000)
	n, err := rand.Int(rand.Reader, max)
	if err != nil {
		return "000000"
	}
	return fmt.Sprintf("%06d", n.Int64())
}

// StatusFile — start.bat 显示 + 手机配对需要看到 PIN
type StatusFile struct {
	Name      string `json:"name"`
	Host      string `json:"host"`
	IP        string `json:"ip"`
	Port      int    `json:"port"`
	Agent     string `json:"agent"`
	StartedAt string `json:"startedAt"`
	Running   bool   `json:"running"`
}

func writeStatus(cfg *Config, host, ip string) {
	st := StatusFile{
		Name:      "Pulse Agent",
		Host:      host,
		IP:        ip,
		Port:      cfg.Port,
		Agent:     agentVersion,
		StartedAt: timeNowString(),
		Running:   true,
	}
	if data, err := json.MarshalIndent(st, "", "  "); err == nil {
		_ = os.WriteFile(filepath.Join(defaultCfgDir(), "status.json"), data, 0o644)
	}
}

func timeNowString() string { return time.Now().Format("2006-01-02 15:04:05") }
