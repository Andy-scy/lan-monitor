package main

// web.go — 内置图形化控制台：浏览器打开 http://电脑IP:端口/ 即可管理。
// 本机（127.0.0.1）自动信任；局域网访问需 PIN 配对。

import (
	"encoding/json"
	"net"
	"net/http"
	"os"
	"os/exec"
	"syscall"
	"time"
)

const webHTML = `<!DOCTYPE html>
<html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Pulse 控制台</title>
<style>
 body{background:#0c0f14;color:#e8ecf2;font-family:system-ui;margin:0;padding:24px;max-width:600px;margin-inline:auto}
 h1{font-size:22px;margin:0 0 4px} p.sub{color:#8b95a5;font-size:13px;margin:0 0 20px}
 .card{background:#141922;border:1px solid rgba(255,255,255,.1);border-radius:14px;padding:16px;margin-bottom:14px}
 label{display:block;font-size:12px;color:#8b95a5;margin:10px 0 4px}
 input{width:100%;box-sizing:border-box;background:#1b2230;border:1px solid rgba(255,255,255,.08);color:#e8ecf2;border-radius:8px;padding:9px 10px;font-size:14px}
 button{background:#2b6cb0;color:#fff;border:0;border-radius:10px;padding:10px 16px;font-size:14px;margin:8px 6px 0 0;cursor:pointer}
 button.gray{background:#2a3342} button.red{background:#b0432b}
 .row{display:flex;gap:10px} .row>div{flex:1}
 #msg{margin-top:14px;color:#5ad394;font-size:13px;min-height:20px}
 .kv{font-size:13px;color:#8b95a5;line-height:1.9;margin:6px 0}
</style></head><body>
<h1>Pulse 控制台</h1>
<p class="sub" id="info">正在连接…</p>
</div>
<div class="card">
  <b>服务配置</b>
  <div class="row">
    <div><label>HTTP 端口</label><input id="port"></div>
    <div><label>发现端口 (UDP)</label><input id="dport"></div>
  </div>
  <div class="row">
    <div><label>推送间隔 (ms, 500-10000)</label><input id="interval"></div>
    <div><label>绑定 IP（留空=全部网卡）</label><input id="bind"></div>
  </div>
  <label>访问 Token（手机配对后自动持有）</label><input id="token" readonly>
  <button onclick="save()">保存配置（重启后生效）</button>
</div>
<div class="card">
  <b>维护</b>
  <p class="kv">一键优化 = 整理所有进程的工作集，释放物理内存（用户态，不结束任何进程）。</p>
  <button onclick="optimize()">一键优化内存</button>
  <button class="gray" onclick="act('/api/restart','服务已重启')">重启服务</button>
  <button class="red" onclick="act('/api/stop','服务已停止。重新双击 start.bat 即可启动')">停止服务</button>
</div>
<div id="msg"></div>
<script>
var token = sessionStorage.getItem('t') || '';
function msg(s){ document.getElementById('msg').textContent = s }
function api(path, opt, cb){
  var h = {'Content-Type':'application/json'};
  if (token) h['Authorization'] = 'Bearer ' + token;
  fetch(path, Object.assign({headers:h}, opt||{})).then(function(r){
    if (r.status === 401 || r.status === 403) { msg('需要配对（输入 PIN）'); throw 0 }
    return r.json()
  }).then(cb).catch(function(e){ if (e !== 0) msg('请求失败：' + e) })
}
function pair(){
  var pin = document.getElementById('pin').value.trim();
  fetch('/api/pair', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({pin:pin})})
    .then(function(r){ return r.json() }).then(function(d){
      if (d.token) { token = d.token; sessionStorage.setItem('t', token); msg('配对成功'); fetchConfig() }
      else msg('PIN 不正确')
    })
}
function load(){
  fetch('/api/local-token').then(function(r){ return r.status === 200 ? r.json() : null })
    .then(function(d){
      if (d && d.token) { token = d.token; sessionStorage.setItem('t', token); fetchConfig() }
      else api('/api/pair', {method:'POST', body:'{}'}, function(d2){
        if (d2 && d2.token) { token = d2.token; sessionStorage.setItem('t', token); fetchConfig() }
      })
    })
}
function fetchConfig(){
  api('/api/config', null, function(c){
    if (!c || !c.port) return
    document.getElementById('port').value = c.port
    document.getElementById('dport').value = c.discoveryPort
    document.getElementById('interval').value = c.intervalMs
    document.getElementById('bind').value = c.bindIP || ''
    document.getElementById('token').value = c.token
    document.getElementById('info').textContent = c.host + ' · ' + (c.ip || '') + ' · Agent v' + c.agent
  })
}
function save(){
  api('/api/config', {method:'POST', body:JSON.stringify({
    port: +document.getElementById('port').value,
    discoveryPort: +document.getElementById('dport').value,
    intervalMs: +document.getElementById('interval').value,
    bindIP: document.getElementById('bind').value
  })}, function(d){ msg(d.ok ? '已保存，重启服务后生效' : '保存失败') })
}
function optimize(){
  api('/api/optimize', {method:'POST'}, function(d){
    msg('内存优化完成：释放 ' + (d.freedMB || 0).toFixed(1) + ' MB · 整理 ' + (d.swept || 0) + ' 个进程（跳过 ' + (d.skipped || 0) + '）')
  })
}
function act(path, okMsg){
  api(path, {method:'POST'}, function(d){
    if (d.ok) { msg(okMsg); setTimeout(function(){ location.reload() }, 1500) }
  })
}
load()
</script></body></html>`

func handleWeb(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_, _ = w.Write([]byte(webHTML))
}

func isLocal(r *http.Request) bool {
	ip, _, _ := net.SplitHostPort(r.RemoteAddr)
	return ip == "127.0.0.1" || ip == "::1"
}

func handleLocalToken(w http.ResponseWriter, r *http.Request) {
	if !isLocal(r) {
		http.Error(w, `{"error":"local only"}`, http.StatusForbidden)
		return
	}
	writeJSON(w, 200, map[string]string{"token": theCfg.Token})
}

func handleConfig(w http.ResponseWriter, r *http.Request) {
	if !isLocal(r) && !theTokenOK(r) {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	switch r.Method {
	case http.MethodGet:
		snap := theCollector.Snapshot()
		host, ip := "", ""
		if snap != nil && snap.Sys != nil {
			host = snap.Sys.Host
			if snap.Sys.IP != nil {
				ip = *snap.Sys.IP
			}
		}
		writeJSON(w, 200, map[string]any{
			"port": theCfg.Port, "discoveryPort": theCfg.DiscoveryPort,
			"intervalMs": theCfg.IntervalMs, "bindIP": theCfg.BindIP,
			"token": theCfg.Token, "devicePin": theCfg.DevicePin,
			"host": host, "ip": ip, "agent": agentVersion,
		})
	case http.MethodPost:
		var body struct {
			Port          int    `json:"port"`
			DiscoveryPort int    `json:"discoveryPort"`
			IntervalMs    int    `json:"intervalMs"`
			BindIP        string `json:"bindIP"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			http.Error(w, `{"error":"bad request"}`, http.StatusBadRequest)
			return
		}
		if body.Port < 1024 || body.Port > 65535 || body.DiscoveryPort < 1024 || body.DiscoveryPort > 65535 ||
			body.IntervalMs < 500 || body.IntervalMs > 10000 {
			http.Error(w, `{"error":"参数超出范围"}`, http.StatusBadRequest)
			return
		}
		cfg := loadConfig()
		cfg.Port, cfg.DiscoveryPort, cfg.IntervalMs, cfg.BindIP = body.Port, body.DiscoveryPort, body.IntervalMs, body.BindIP
		saveConfig(cfg)
		theCfg.Port, theCfg.DiscoveryPort, theCfg.IntervalMs, theCfg.BindIP = cfg.Port, cfg.DiscoveryPort, cfg.IntervalMs, cfg.BindIP
		writeJSON(w, 200, map[string]any{"ok": true, "needRestart": true})
	}
}

func handlePinReset(w http.ResponseWriter, r *http.Request) {
	if !isLocal(r) && !theTokenOK(r) {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	cfg := loadConfig()
	cfg.DevicePin = randPIN()
	saveConfig(cfg)
	theCfg.DevicePin = cfg.DevicePin
	writeJSON(w, 200, map[string]string{"pin": cfg.DevicePin})
}

func handleRestart(w http.ResponseWriter, r *http.Request) {
	if !isLocal(r) && !theTokenOK(r) {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusForbidden)
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true})
	go func() {
		time.Sleep(500 * time.Millisecond)
		if exe, err := os.Executable(); err == nil {
			cmd := exec.Command(exe)
			cmd.SysProcAttr = &syscall.SysProcAttr{CreationFlags: 0x00000008 | 0x00000200} // DETACHED | NEW_GROUP
			_ = cmd.Start()
		}
		logf("restart requested via web console")
		os.Exit(0)
	}()
}

func handleStop(w http.ResponseWriter, r *http.Request) {
	if !isLocal(r) && !theTokenOK(r) {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusForbidden)
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true})
	go func() {
		time.Sleep(500 * time.Millisecond)
		logf("stop requested via web console")
		os.Exit(0)
	}()
}
