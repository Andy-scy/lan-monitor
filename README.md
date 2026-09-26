# Pulse · 局域网 PC 硬件监控

双击 BAT，手机自动发现电脑，实时查看 CPU / GPU / 内存 / 磁盘 / 网络与温度。

**产品原则：电脑端极简，手机端精致。**

- Windows 端：单个 `monitor.exe`（6.5MB，无窗口，常驻内存 ~25MB，CPU <0.5%），无安装、无数据库、无云、无账号
- Android 端：Kotlin + Jetpack Compose 原生 App，UDP 自动发现 + WebSocket 实时推送 + 自绘图表
- 拿不到的硬件数据一律显示「不支持」，**绝不伪造**

```
lan-monitor/
├── dist/               ← 直接使用这个目录
│   ├── monitor.exe       监控服务（无窗口）
│   ├── start.bat         双击启动
│   ├── stop.bat          双击停止
│   ├── install-firewall.bat  首次使用运行一次（需管理员）
│   └── config.json       首次启动自动生成（端口/Token/PIN）
├── android/            ← Android 工程源码
├── windows-agent/      ← Go 源码
├── docs/               ← 调研报告 / 架构设计 / 数据协议
└── tools/              ← 构建 / 长稳测试脚本
```

---

## 一、启动 Windows Agent

1. 把 `dist/` 放到电脑任意目录（如 `D:\Pulse\`）。
2. **先双击 `install-firewall.bat`**（右键「以管理员身份运行」，只需一次）——放行 TCP 42710 与 UDP 42711，仅限专用网络。
3. 双击 **`start.bat`**：窗口会显示本机 IP、端口和 6 位配对 PIN，随后窗口自动消失（服务在后台运行，不弹黑窗）。
4. 双击 **`stop.bat`** 停止服务。

首次启动后 `config.json` 内容（可改，改完重启服务）：

| 字段 | 说明 |
|---|---|
| `port` | HTTP/WebSocket 端口，默认 42710 |
| `discoveryPort` | UDP 发现端口，默认 42711 |
| `intervalMs` | 推送间隔，默认 1000（最小 500） |
| `bindIP` | 绑定 IP，留空=全部网卡 |
| `token` | 访问令牌（自动生成；删除此文件字段可重置全部配对） |
| `devicePin` | 6 位配对 PIN |

`status.json` 记录当前 IP / 端口 / PIN，`logs/monitor.log` 为滚动日志（3×512KB 封顶，不疯写磁盘）。

## 二、安装 Android App

方式 A：用数据线连接手机，执行 `tools\install-apk.bat`（或 `adb install -r Pulse-debug.apk`）。
方式 B：把 `Pulse-debug.apk` 传到手机直接安装（需允许「安装未知应用」）。

APK 输出在 `android/app/build/outputs/apk/debug/`，也可自行构建：
`cd android && gradle assembleDebug`（需 JDK 17+ 与 Android SDK）。

## 三、让手机连接电脑（约 20 秒）

1. 手机与电脑连**同一个 Wi-Fi/路由器**。
2. 打开 Pulse → 自动雷达扫描 → 点击发现的电脑。
3. 首次连接弹出 PIN 输入框 → 输入 `start.bat` 窗口显示（或 `dist\status.json` 里）的 6 位 PIN → 配对成功，长期免密。
4. 未发现时：点「手动添加设备」输入电脑 IP（端口 42710）→ 再输入 PIN。
5. 长按设备卡片可移除；多台电脑逐一添加即可。

电脑关机/断网/睡眠时 App 显示「离线」，恢复后自动「重连中 → 在线」；数据超 5 秒未更新显示「数据滞后」并整体置灰，不会拿旧数据骗人。

## 四、支持哪些硬件

| 指标 | 支持范围 | 拿不到时 |
|---|---|---|
| CPU 使用率/每核 | 全部 x86/x64 | — |
| CPU 频率 | Win8+（有效频率） | 显示 — |
| CPU 温度 | 有 ACPI 热区且数值随负载波动（多数笔记本有；恒定假值会被自动识别为不可信） | 「不支持」 |
| GPU 使用率/显存占用 | **NVIDIA / AMD / Intel 全支持**（WDDM 系统计数器，Win10 1709+） | 「不支持」 |
| GPU 温度/功耗/频率 | NVIDIA（NVML 随驱动分发） | 「不支持」（AMD/Intel 暂无公开安全通道） |
| GPU 名称/显存总量 | DXGI（正常机器）；被虚拟显示驱动干扰时回退注册表 | 名称可能显示为显卡 ID |
| 内存 / Commit | 全部 | — |
| 磁盘容量/读写速率 | 全部本地卷 | — |
| 网络速率 | 全部物理网卡（自动排除隧道/虚拟网卡，显示当前活动网卡） | — |

> 本项目在 i7-1260P + Intel Iris Xe 核显笔记本上全链路实测；NVIDIA 路径按 NVML 标准实现。深度传感器（风扇转速、每核温度、AMD 功耗）需要内核驱动，为守住「免管理员+无杀软误报」暂不内置，架构预留了 LibreHardwareMonitor 桥接扩展点。

## 五、常见问题

**扫描不到电脑？**
① 电脑上先跑 start.bat；② 确认同一网段（有些路由器开了 AP 隔离，关掉或用「手动添加」）；③ 跑过 install-firewall.bat 了吗；④ 手机开了「随机 MAC」换网络后重试扫描。

**配对 PIN 在哪看？**
start.bat 启动窗口 / `dist\status.json` 的 `pin` 字段。想换 PIN：改 config.json 的 `devicePin` 后重启服务。

**手机上显示「待配对」？**
该设备被重置过 token（如重装 Agent）。重新输入 PIN 即可。

**想让刷新更省电？** Dashboard 暂未开放开关，App 已自动按需降频；如需手动改 Agent 推送间隔改 `intervalMs`。

**安全吗？**
仅监听局域网，HTTP/WS 需 Bearer Token（配对获得），配对接口限速 5 次/分钟。**请勿把 42710 端口转发到公网。**

## 六、防火墙配置（手动版）

`install-firewall.bat` 等价于：

```bat
netsh advfirewall firewall add rule name="Pulse Monitor" dir=in action=allow protocol=TCP localport=42710 profile=private,domain
netsh advfirewall firewall add rule name="Pulse Monitor Discovery" dir=in action=allow protocol=UDP localport=42711 profile=private,domain
```

仅「专用网络」放行。若电脑连的是「公用网络」类型的 Wi-Fi，请先把它改为专用，或临时放行。

## 七、长稳测试

```powershell
# 1 小时（默认）：输出 tools/soak/soak.jsonl + soak-process.csv
powershell -ExecutionPolicy Bypass -File tools\soak.ps1 -Minutes 60
# 24 小时
powershell -ExecutionPolicy Bypass -File tools\soak.ps1 -Minutes 1440
```

判定标准：Agent 工作集增长 <10MB、无快照断流、句柄数稳定。

---

## 从源码构建

**Windows Agent**（Go 1.22+）：

```bash
cd windows-agent
go build -trimpath -ldflags "-s -w -H=windowsgui" -o ../dist/monitor.exe .
```

**Android**（JDK 17+，Android SDK 35）：

```bash
cd android && gradle assembleDebug
```

技术文档：[docs/调研报告.md](docs/调研报告.md) · [docs/架构设计.md](docs/架构设计.md) · [docs/数据协议.md](docs/数据协议.md)
