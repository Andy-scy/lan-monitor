package main

// collect.go — 采集编排：一条 PDH query 全量挂计数器，每 tick 一次 collect，
// 汇同 Win32 直调结果组装 Snapshot。首两个 tick 仅作速率基线，不对外发布。

import (
	"fmt"
	"math"
	"sort"
	"strings"
	"sync"
	"time"
)

type Collector struct {
	mu       sync.Mutex
	snap     *Snapshot
	q        *pdhQuery
	tick     int
	interval time.Duration

	// 静态信息（启动时取一次）
	cpuName    string
	baseMhz    float64
	physCores  int
	logiCores  int
	osName     string
	osVersion  string
	hostname   string
	dxgiGPUs   []*dxgiAdapter
	regGPUs    []*gpuAdapterInfo // 注册表显示适配器（DXGI 兜底）
	nvml       *nvmlAPI
	nvmlByCard map[int]*nvmlDevice // 卡序号 → NVML 设备

	// CPU 温度可信度滑窗
	tzRing []float64
}

func NewCollector(interval time.Duration) (*Collector, error) {
	c := &Collector{interval: interval, nvmlByCard: map[int]*nvmlDevice{}}
	c.loadStatic()
	q, err := openPdhQuery()
	if err != nil {
		return nil, err
	}
	c.q = q
	q.add(`\Processor Information(_Total)\% Processor Time`)
	q.add(`\Processor Information(*)\% Processor Time`)
	q.add(`\Processor Information(_Total)\% Processor Performance`)
	q.add(`\Processor Information(_Total)\Processor Frequency`)
	q.add(`\Thermal Zone Information(*)\Temperature`)
	q.add(`\Network Interface(*)\Bytes Sent/sec`)
	q.add(`\Network Interface(*)\Bytes Received/sec`)
	q.add(`\PhysicalDisk(*)\Disk Read Bytes/sec`)
	q.add(`\PhysicalDisk(*)\Disk Write Bytes/sec`)
	q.add(`\GPU Engine(*)\Utilization Percentage`)
	q.add(`\GPU Adapter Memory(*)\Dedicated Usage`)
	q.add(`\GPU Adapter Memory(*)\Shared Usage`)
	_ = q.collect() // 速率基线
	if err := q.collect(); err != nil {
		return nil, err
	}
	c.nvml = openNVML()
	c.dxgiGPUs = enumDXGI()
	c.pairNVML()
	return c, nil
}

// Snapshot 返回最近一次快照（浅拷贝引用即可，快照整体不可变）
func (c *Collector) Snapshot() *Snapshot {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.snap
}

func (c *Collector) Run(stop <-chan struct{}) {
	t := time.NewTicker(c.interval)
	defer t.Stop()
	for {
		select {
		case <-stop:
			return
		case <-t.C:
			c.tickOnce()
		}
	}
}

func (c *Collector) tickOnce() {
	defer func() {
		if r := recover(); r != nil {
			dbg("tick panic: %v", r)
		}
	}()
	c.tick++
	// 空闲省电：90 秒无客户端时降为 1/5 频率采样（CPU≈0）
	if lastClientSeen.Load() != 0 && time.Now().UnixMilli()-lastClientSeen.Load() > 90_000 && c.tick%5 != 0 {
		return
	}
	q := c.q
	if err := q.collect(); err != nil {
		dbg("collect err: %v", err)
		return
	}

	s := &Snapshot{V: 1, TS: time.Now().UnixMilli()}
	s.UptimeSec = uptimeSeconds()
	s.Sys = c.sysSnapshot()
	s.CPU = c.cpuSnapshot(q)
	s.GPUs = c.gpuSnapshot(q)
	s.Mem = memSnapshot()
	s.Disks = diskSnapshot(q)
	s.Net = netSnapshot(q)

	c.mu.Lock()
	c.snap = s
	c.mu.Unlock()
}

// ---------- CPU ----------

func (c *Collector) cpuSnapshot(q *pdhQuery) *CPUInfo {
	cpu := &CPUInfo{CoresLogical: c.logiCores, CoresPhysical: c.physCores}
	if c.cpuName != "" {
		n := c.cpuName
		cpu.Name = &n
	}
	if v := q.readSingle(`\Processor Information(_Total)\% Processor Time`); !isNan(v) && v >= 0 && v <= 100.5 {
		u := clampPct(v)
		cpu.Usage = &u
	}
	if f := q.readSingle(`\Processor Information(_Total)\Processor Frequency`); !isNan(f) && f > 100 && f < 20000 {
		fv := f
		cpu.FreqMhz = &fv
	}
	if c.baseMhz > 0 {
		b := c.baseMhz
		cpu.BaseMhz = &b
	}

	// 每核：实例名 "0,3" 或 "3"；按逻辑核序号填充
	if m := q.readArray(`\Processor Information(*)\% Processor Time`); m != nil {
		per := make([]float64, c.logiCores)
		filled := 0
		for name, v := range m {
			if name == "_Total" || isNan(v) {
				continue
			}
			idx := coreIndexFromInstance(name)
			if idx >= 0 && idx < c.logiCores {
				per[idx] = clampPct(v)
				filled++
			}
		}
		if filled > 0 {
			cpu.PerCore = per
		}
	}

	// 温度：热区最高值（PDH 单位为开尔文），恒定值判不可信
	if m := q.readArray(`\Thermal Zone Information(*)\Temperature`); m != nil {
		maxC := math.NaN()
		for _, k := range m {
			cel := k - 273.15
			if cel < 5 || cel > 115 {
				continue
			}
			if math.IsNaN(maxC) || cel > maxC {
				maxC = cel
			}
		}
		if !math.IsNaN(maxC) {
			c.tzRing = append(c.tzRing, maxC)
			if len(c.tzRing) > 64 {
				c.tzRing = c.tzRing[1:]
			}
			if c.tempTrustworthy() {
				tv := round1(maxC)
				cpu.Temp = &tv
				src := "thermalZone"
				cpu.TempSource = &src
			}
		}
	}
	return cpu
}

func (c *Collector) tempTrustworthy() bool {
	// 需要至少 45 个样本且波动 ≥1.2℃ 才认（防 ACPI 恒定假值）
	if len(c.tzRing) < 45 {
		return false
	}
	mn, mx := c.tzRing[0], c.tzRing[0]
	for _, v := range c.tzRing {
		mn = math.Min(mn, v)
		mx = math.Max(mx, v)
	}
	return mx-mn >= 1.2
}

func coreIndexFromInstance(name string) int {
	// "0,5" → 5；"5" → 5；其他 -1
	name = strings.TrimSpace(name)
	if i := strings.LastIndex(name, ","); i >= 0 {
		name = name[i+1:]
	}
	n := 0
	if len(name) == 0 || len(name) > 3 {
		return -1
	}
	for _, ch := range name {
		if ch < '0' || ch > '9' {
			return -1
		}
		n = n*10 + int(ch-'0')
	}
	return n
}

// ---------- GPU ----------

func (c *Collector) gpuSnapshot(q *pdhQuery) []*GPU {
	// 1) 从 PDH 实例名收集 LUID 维度的用量/显存
	type perLuid struct {
		pidEng map[uint32]map[string]float64 // pid → engtype → Σ
		ded    float64
		shr    float64
		hasMem bool
	}
	luids := map[[2]uint32]*perLuid{}
	if m := q.readArray(`\GPU Engine(*)\Utilization Percentage`); m != nil {
		for inst, v := range m {
			luid, pid, eng, ok := parseEngineInstance(inst)
			if !ok || isNan(v) || v < 0 {
				continue
			}
			if luids[luid] == nil {
				luids[luid] = &perLuid{pidEng: map[uint32]map[string]float64{}}
			}
			if luids[luid].pidEng[pid] == nil {
				luids[luid].pidEng[pid] = map[string]float64{}
			}
			luids[luid].pidEng[pid][eng] += v
		}
	}
	if m := q.readArray(`\GPU Adapter Memory(*)\Dedicated Usage`); m != nil {
		for inst, v := range m {
			if luid, ok := parseLUIDInstance(inst); ok && !isNan(v) && v >= 0 {
				if luids[luid] == nil {
					luids[luid] = &perLuid{pidEng: map[uint32]map[string]float64{}}
				}
				luids[luid].ded = v
				luids[luid].hasMem = true
			}
		}
	}
	if m := q.readArray(`\GPU Adapter Memory(*)\Shared Usage`); m != nil {
		for inst, v := range m {
			if luid, ok := parseLUIDInstance(inst); ok && !isNan(v) && v >= 0 {
				if luids[luid] == nil {
					luids[luid] = &perLuid{pidEng: map[uint32]map[string]float64{}}
				}
				luids[luid].shr = v
			}
		}
	}

	// 2) 适配器清单：DXGI 优先；DXGI 不可用时基于 PDH LUID + 注册表名兜底
	type adapter struct {
		info *gpuAdapterInfo // 名称/厂商/显存来源
		luid [2]uint32
		pl   *perLuid
	}
	var adapters []adapter
	if len(c.dxgiGPUs) > 0 {
		for _, ad := range c.dxgiGPUs {
			key := [2]uint32{ad.luidHigh, ad.luidLow}
			gi := &gpuAdapterInfo{desc: ad.desc, vendor: ad.vendor(), vramMB: bytesToMB(float64(ad.dedicatedBytes)),
				luidLow: ad.luidLow, luidHigh: ad.luidHigh, hasLuid: true}
			adapters = append(adapters, adapter{info: gi, luid: key, pl: luids[key]})
		}
	} else {
		// 兜底：选有活动/显存的 LUID；注册表 PCI 显卡与 LUID 一一对应时赋予名称
		type cand struct {
			key    [2]uint32
			pl     *perLuid
			weight float64
		}
		var cands []cand
		for key, pl := range luids {
			w := pl.ded + pl.shr
			for _, engs := range pl.pidEng {
				for _, v := range engs {
					w += v
				}
			}
			if w > 0 {
				cands = append(cands, cand{key: key, pl: pl, weight: w})
			}
		}
		sort.Slice(cands, func(i, j int) bool { return cands[i].weight > cands[j].weight })
		regGPUs := c.regGPUs
		if len(cands) == 1 && len(regGPUs) == 1 {
			gi := &gpuAdapterInfo{desc: regGPUs[0].desc, vendor: regGPUs[0].vendor, vramMB: regGPUs[0].vramMB,
				luidLow: cands[0].key[1], luidHigh: cands[0].key[0], hasLuid: true}
			adapters = append(adapters, adapter{info: gi, luid: cands[0].key, pl: cands[0].pl})
		} else {
			for i, cd := range cands {
				var gi *gpuAdapterInfo
				if i < len(regGPUs) {
					gi = &gpuAdapterInfo{desc: regGPUs[i].desc, vendor: regGPUs[i].vendor, vramMB: regGPUs[i].vramMB}
				} else {
					gi = &gpuAdapterInfo{}
				}
				adapters = append(adapters, adapter{info: gi, luid: cd.key, pl: cd.pl})
			}
		}
	}

	out := make([]*GPU, 0, len(adapters))
	for i, ad := range adapters {
		if ad.pl == nil && ad.info.desc == "" {
			continue // 无任何数据的幽灵适配器
		}
		// 过滤虚拟适配器：无名称、无活动、无专用显存占用
		if ad.info.desc == "" {
			idle := ad.pl == nil
			if !idle && ad.pl.ded == 0 {
				busy := false
				for _, engs := range ad.pl.pidEng {
					for _, v := range engs {
						if v > 0.5 {
							busy = true
						}
					}
				}
				if !busy {
					idle = true
				}
			}
			if idle {
				continue
			}
		}
		g := &GPU{ID: fmt.Sprintf("luid-0x%08X%08X", ad.luid[0], ad.luid[1]), Vendor: ad.info.vendor}
		if ad.info.desc != "" {
			name := ad.info.desc
			g.Name = &name
		}
		if ad.info.vramMB > 0 {
			t := round1(ad.info.vramMB)
			g.MemTotalMB = &t
		}
		if ad.pl != nil {
			if len(ad.pl.pidEng) > 0 {
				total := 0.0
				for _, engs := range ad.pl.pidEng {
					mx := 0.0
					for _, v := range engs {
						mx = math.Max(mx, v)
					}
					total += mx
				}
				u := clampPct(total)
				g.Usage = &u
			}
			if ad.pl.hasMem {
				used := bytesToMB(ad.pl.ded)
				g.MemUsedMB = &used
				shr := bytesToMB(ad.pl.shr)
				g.MemSharedUsedMB = &shr
			}
		}
		if nv := c.nvmlByCard[i]; nv != nil {
			nv.fill(g)
		}
		out = append(out, g)
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

func (c *Collector) pairNVML() {
	if c.nvml == nil {
		return
	}
	devs := c.nvml.devices()
	// 简单配对：把 NVML 设备按顺序对应到厂商清单里的 NVIDIA 卡（单卡场景完全准确；多卡按序）
	var nvidiaIdx []int
	if len(c.dxgiGPUs) > 0 {
		for i, ad := range c.dxgiGPUs {
			if ad.vendor() == "NVIDIA" {
				nvidiaIdx = append(nvidiaIdx, i)
			}
		}
	} else {
		for i, rg := range c.regGPUs {
			if rg.vendor == "NVIDIA" {
				nvidiaIdx = append(nvidiaIdx, i)
			}
		}
	}
	for j, nd := range devs {
		if j < len(nvidiaIdx) {
			c.nvmlByCard[nvidiaIdx[j]] = nd
		}
	}
}

// ---------- 系统静态信息 ----------

func (c *Collector) loadStatic() {
	c.hostname = getHostname()
	c.osName, c.osVersion = getOSInfo()
	c.cpuName, c.physCores, c.logiCores = getCPUInfo()
	c.baseMhz = getBaseClockMhz()
	c.regGPUs = registryGPUs()
}

func (c *Collector) sysSnapshot() *SysInfo {
	return &SysInfo{
		Host:      c.hostname,
		OS:        c.osName,
		OSVersion: c.osVersion,
		IP:        primaryLANIP(),
		Agent:     agentVersion,
	}
}

func round1(v float64) float64 { return math.Round(v*10) / 10 }
func clampPct(v float64) float64 {
	v = math.Min(v, 100)
	v = math.Max(v, 0)
	return round1(v)
}
func bytesToMB(b float64) float64 { return math.Round(b/1048576*10) / 10 }
