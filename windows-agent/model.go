package main

// model.go — 数据协议 v1 的 JSON 模型。所有不可得字段必须为 null（指针/slice nil），
// 与 0 值严格区分，绝不伪造数据。

type Snapshot struct {
	V         int       `json:"v"`
	TS        int64     `json:"ts"`        // Unix 毫秒
	UptimeSec *float64  `json:"uptimeSec"` // 系统运行时间（秒）
	Sys       *SysInfo  `json:"sys"`
	CPU       *CPUInfo  `json:"cpu"`
	GPUs      []*GPU    `json:"gpus"` // 多显卡数组；整体失败为 null
	Mem       *Memory   `json:"mem"`
	Disks     []*Disk   `json:"disks"`
	Net       *Network  `json:"net"`
}

type SysInfo struct {
	Host      string  `json:"host"`
	OS        string  `json:"os"`
	OSVersion string  `json:"osVersion"`
	IP        *string `json:"ip"`     // 主局域网 IPv4
	Agent     string  `json:"agent"`
}

type CPUInfo struct {
	Name          *string   `json:"name"`
	Usage         *float64  `json:"usage"` // 0-100
	Temp          *float64  `json:"temp"`  // ℃；无可信传感器为 null
	TempSource    *string   `json:"tempSource"`
	FreqMhz       *float64  `json:"freqMhz"` // 有效频率
	BaseMhz       *float64  `json:"baseMhz"`
	CoresLogical  int       `json:"coresLogical"`
	CoresPhysical int       `json:"coresPhysical"`
	PerCore       []float64 `json:"perCore"` // 长度=逻辑核心数，失败为 null
}

type GPU struct {
	ID             string   `json:"id"` // luid-0xHHHHHHHH
	Name           *string  `json:"name"`
	Vendor         string   `json:"vendor"` // INTEL/NVIDIA/AMD/OTHER
	Usage          *float64 `json:"usage"`
	Temp           *float64 `json:"temp"`
	PowerW         *float64 `json:"powerW"`
	ClockMhz       *float64 `json:"clockMhz"`
	MemUsedMB      *float64 `json:"memUsedMB"`
	MemTotalMB     *float64 `json:"memTotalMB"`
	MemSharedUsedMB *float64 `json:"memSharedUsedMB"`
}

type Memory struct {
	TotalMB       *float64 `json:"totalMB"`
	UsedMB        *float64 `json:"usedMB"`
	AvailableMB   *float64 `json:"availableMB"`
	UsagePct      *float64 `json:"usagePct"`
	CommitUsedMB  *float64 `json:"commitUsedMB"`
	CommitLimitMB *float64 `json:"commitLimitMB"`
}

type Disk struct {
	Drive     string   `json:"drive"` // "C:"
	Label     *string  `json:"label"`
	Removable bool     `json:"removable"`
	TotalMB   *float64 `json:"totalMB"`
	UsedMB    *float64 `json:"usedMB"`
	FreeMB    *float64 `json:"freeMB"`
	UsagePct  *float64 `json:"usagePct"`
	ReadBps   *float64 `json:"readBps"`
	WriteBps  *float64 `json:"writeBps"`
}

type NetIface struct {
	Name    string   `json:"name"`
	Desc    *string  `json:"desc"`
	DownBps *float64 `json:"downBps"`
	UpBps   *float64 `json:"upBps"`
	Active  bool     `json:"active"`
}

type Network struct {
	DownloadBps *float64    `json:"downloadBps"`
	UploadBps   *float64    `json:"uploadBps"`
	TotalRx     *int64      `json:"totalRxBytes"` // v1 预留，可能为 null
	TotalTx     *int64      `json:"totalTxBytes"`
	Active      *string     `json:"active"`
	ActiveDesc  *string     `json:"activeDesc"`
	Ifaces      []*NetIface `json:"interfaces"`
}

// Hello 供 /api/hello 与发现应答使用
type Hello struct {
	Proto           string  `json:"proto,omitempty"`
	Name            string  `json:"name"`
	Host            string  `json:"host"`
	OS              string  `json:"os"`
	Port            int     `json:"port"`
	ID              string  `json:"id"`
	Agent           string  `json:"agent"`
	PairingRequired bool    `json:"pairingRequired"`
}

const (
	agentVersion  = "1.0.0"
	protoMagic    = "PULSE:DISCOVER:v1"
	defaultPort   = 42710
	defaultDiscPort = 42711
)
