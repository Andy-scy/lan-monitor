package main

// discovery.go — UDP 发现应答：监听 :42711/udp，收到 PULSE:DISCOVER:v1 后
// 以单播 JSON 回应（含 host/os/port/id/pairingRequired）。

import (
	"encoding/json"
	"net"
	"time"
)

type Discovery struct {
	cfg       *Config
	collector *Collector
	conn      *net.UDPConn
}

func NewDiscovery(cfg *Config, c *Collector) *Discovery {
	return &Discovery{cfg: cfg, collector: c}
}

func (d *Discovery) Run(stop <-chan struct{}) {
	addr := &net.UDPAddr{IP: net.IPv4zero, Port: d.cfg.DiscoveryPort}
	conn, err := net.ListenUDP("udp", addr)
	if err != nil {
		logf("discovery listen failed: %v", err)
		return
	}
	d.conn = conn
	defer conn.Close()
	logf("discovery listening on udp/%d", d.cfg.DiscoveryPort)

	buf := make([]byte, 256)
	for {
		select {
		case <-stop:
			return
		default:
		}
		if err := conn.SetReadDeadline(time.Now().Add(time.Second)); err != nil {
			return
		}
		n, remote, err := conn.ReadFromUDP(buf)
		if err != nil {
			continue // 超时后回到 select 检查 stop
		}
		if n < len(protoMagic) || string(buf[:n]) != protoMagic {
			continue
		}
		resp := d.buildReply()
		if data, err := json.Marshal(resp); err == nil {
			_, _ = conn.WriteToUDP(data, remote)
		}
	}
}

func (d *Discovery) buildReply() Hello {
	h := Hello{Proto: "PULSE:v1", Name: "Pulse Agent", Port: d.cfg.Port, ID: d.cfg.DeviceID, Agent: agentVersion}
	snap := d.collector.Snapshot()
	if snap != nil && snap.Sys != nil {
		h.Host = snap.Sys.Host
		h.OS = snap.Sys.OS
	}
	h.PairingRequired = false
	return h
}
