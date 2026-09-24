#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Color管理器 WebUI 服务器（零依赖，Python3 标准库，纯本地工作无需联网）
- root 环境运行（Termux: tsu python3 server.py）→ 读取真实电池/CPU sysfs，可直接切换调速器
- 无 root 运行 → 自动进入演示模式（UI 完整，数据为模拟，参数保存有效）
- 仅绑定本机回环 127.0.0.1，局域网其他设备无法访问
- 访问: http://127.0.0.1:8765
"""
import json, os, random, subprocess, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

BASE   = os.path.dirname(os.path.abspath(__file__))
STATIC = os.path.join(BASE, "static")
# 参数存储位置可用环境变量覆盖（例如放到模块升级不会清除的工作目录）
DATA_FILE = os.environ.get("COLOR_WEBUI_DATA", os.path.join(BASE, "data.json"))
PORT = 8765

BAT = "/sys/class/power_supply/battery"
CPU_BASE = "/sys/devices/system/cpu"

PRESET_GOVERNORS = ["conservative", "walt", "ips", "sugov_next",
                    "scx", "hmbird", "powersave", "performance", "schedutil"]
MODE_KEYS  = ["powersave", "balance", "performance", "fast"]
MODE_NAMES = {"powersave": "省电模式", "balance": "均衡模式",
              "performance": "性能模式", "fast": "极速模式"}

# ---------------- 持久化存储 ----------------
def defaults():
    return {
        "modes": {
            "powersave":    {"opt2": 0,  "cpuMax": 42,  "cpuMin": 5,  "llcc": 350000,
                             "uclampDisplay": 6,  "uclampSsfg": 5,  "uclampTouch": 8,
                             "uclampMm": 6,  "uclampRt": 3,   "uclampTopApp": 10, "walt1": 0, "walt2": 1500},
            "balance":      {"opt2": 26, "cpuMax": 64,  "cpuMin": 20, "llcc": 680000,
                             "uclampDisplay": 30, "uclampSsfg": 28, "uclampTouch": 40,
                             "uclampMm": 32, "uclampRt": 40,  "uclampTopApp": 26, "walt1": 0, "walt2": 1500},
            "performance":  {"opt2": 52, "cpuMax": 90,  "cpuMin": 35, "llcc": 1220000,
                             "uclampDisplay": 78, "uclampSsfg": 76, "uclampTouch": 92,
                             "uclampMm": 80, "uclampRt": 92,  "uclampTopApp": 74, "walt1": 0, "walt2": 1500},
            "fast":         {"opt2": 88, "cpuMax": 100, "cpuMin": 42, "llcc": 1800000,
                             "uclampDisplay": 82, "uclampSsfg": 80, "uclampTouch": 94,
                             "uclampMm": 84, "uclampRt": 94,  "uclampTopApp": 78, "walt1": 0, "walt2": 1500},
        },
        "governor": {"governor": "conservative", "upThreshold": 98, "downThreshold": 93,
                     "freqStep": 1, "samplingRate": 14000, "targetLoads": 90},
        "cellMode": 0,
    }

_lock = threading.Lock()

def load_data():
    with _lock:
        try:
            with open(DATA_FILE) as f:
                d = json.load(f)
            base = defaults()
            base["modes"].update(d.get("modes", {}))
            base["governor"].update(d.get("governor", {}))
            base["cellMode"] = d.get("cellMode", base["cellMode"])
            return base
        except Exception:
            return defaults()

def save_data(d):
    with _lock:
        with open(DATA_FILE, "w") as f:
            json.dump(d, f, ensure_ascii=False, indent=1)

# ---------------- sysfs 读取 ----------------
def read_int(path):
    try:
        with open(path) as f:
            return int(f.read().strip())
    except Exception:
        return None

def read_str(path):
    try:
        with open(path) as f:
            return f.read().strip()
    except Exception:
        return None

def sys_write(path, val):
    """root 直写优先，失败时尝试 su -c"""
    try:
        with open(path, "w") as f:
            f.write(str(val))
        return True
    except Exception:
        pass
    try:
        r = subprocess.run(["su", "-c", f"echo '{val}' > {path}"],
                           timeout=3, capture_output=True)
        return r.returncode == 0
    except Exception:
        return False

def cpu_count():
    present = read_str(f"{CPU_BASE}/present")
    if present and "-" in present:
        try:
            a, b = present.split("-")
            return int(b) - int(a) + 1
        except Exception:
            pass
    return None

# ---------------- 演示模式模拟器 ----------------
class Demo:
    def __init__(self):
        self.soc, self.volt, self.curr = 84, 4420000, -1230000  # µV / µA
        self.temp, self.cycle = 33.5, 128
        self.status = "Discharging"
        self.gov = "walt"
    def tick(self):
        self.curr = max(-2600000, min(-350000, self.curr + random.randint(-90000, 90000)))
        self.temp = max(28.0, min(41.0, self.temp + random.uniform(-0.2, 0.2)))
        if self.soc > 20 and random.random() < 0.004:
            self.soc -= 1

_demo = Demo()

# ---------------- 业务 API ----------------
def api_status():
    d = load_data()
    soc = read_int(f"{BAT}/capacity")
    demo = soc is None
    ncpu = cpu_count()
    if demo:
        _demo.tick()
        soc, volt, curr = _demo.soc, _demo.volt, _demo.curr
        temp, cycle, status = _demo.temp, _demo.cycle, _demo.status
        temp = round(temp, 1)
        ncpu = ncpu or 8
        avail = PRESET_GOVERNORS
        gov_now = _demo.gov
    else:
        volt = read_int(f"{BAT}/voltage_now")
        curr = read_int(f"{BAT}/current_now")
        t = read_int(f"{BAT}/temp")
        cycle = read_int(f"{BAT}/cycle_count")
        status = read_str(f"{BAT}/status") or "Unknown"
        temp = t / 10.0 if t is not None else None
        ncpu = ncpu or 8
        avail_s = read_str(f"{CPU_BASE}/cpu0/cpufreq/scaling_available_governors")
        avail = [g for g in PRESET_GOVERNORS if avail_s and g in avail_s] or PRESET_GOVERNORS
        gov_now = read_str(f"{CPU_BASE}/cpu0/cpufreq/scaling_governor") or d["governor"]["governor"]
    # 电芯判定：>=4.30V 判为双电芯（串联电芯电压 4.4xV）
    auto_cells = 2 if (volt or 0) >= 4300000 else 1
    cell_mode = d["cellMode"]
    cells = auto_cells if cell_mode == 0 else cell_mode
    power = round(abs(curr) * (volt / 1e6) / 1e6 * cells, 2) if (curr is not None and volt) else None
    return {"demo": demo, "soc": soc, "voltage": round(volt / 1e6, 3),
            "current": curr, "temp": temp, "cycle": cycle, "status": status,
            "charging": "Charging" in (status or ""), "cells": cells,
            "cellMode": cell_mode, "cpuCount": ncpu, "power": power,
            "governor": gov_now, "governorsAvail": avail,
            "modeNames": MODE_NAMES}

def api_modes():
    d = load_data()
    return {"modes": d["modes"], "order": MODE_KEYS, "names": MODE_NAMES}

def api_save_mode(key, mode):
    if key not in MODE_KEYS:
        return {"ok": False, "error": "unknown mode"}
    d = load_data()
    stored = d["modes"].get(key, defaults()["modes"][key])
    for k, v in (mode or {}).items():
        if k in stored:
            stored[k] = int(v)
    d["modes"][key] = stored
    save_data(d)
    return {"ok": True}

def api_governors():
    d = load_data()
    s = api_status()
    return {"presets": PRESET_GOVERNORS, "config": d["governor"],
            "current": s["governor"], "avail": s["governorsAvail"], "demo": s["demo"]}

def api_save_governor(cfg):
    d = load_data()
    g = d["governor"]
    g["governor"] = cfg.get("governor", g["governor"])
    for k in ("upThreshold", "downThreshold", "freqStep", "samplingRate", "targetLoads"):
        if k in cfg and cfg[k] is not None:
            g[k] = int(cfg[k])
    save_data(d)
    # 实时应用（需要 root；演示模式仅保存）
    if api_status()["demo"]:
        return {"ok": True, "applied": False}
    applied = True
    name = g["governor"]
    for i in range(api_status()["cpuCount"]):
        base = f"{CPU_BASE}/cpu{i}/cpufreq"
        if not sys_write(f"{base}/scaling_governor", name):
            applied = False
        if name == "conservative":
            for p, v in (("up_threshold", g["upThreshold"]), ("down_threshold", g["downThreshold"]),
                         ("freq_step", g["freqStep"]), ("sampling_rate", g["samplingRate"])):
                sys_write(f"{base}/conservative/{p}", v)
        elif name == "scx":
            sys_write(f"{base}/scx/target_loads", g["targetLoads"])
    return {"ok": True, "applied": applied}

def api_save_cells(cell_mode):
    d = load_data()
    d["cellMode"] = 0 if cell_mode in (None, 0) else (2 if cell_mode == 2 else 1)
    save_data(d)
    return {"ok": True, "cellMode": d["cellMode"]}

# ---------------- HTTP 服务 ----------------
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _file(self, path):
        try:
            with open(path, "rb") as f:
                body = f.read()
            ctype = "text/html; charset=utf-8" if path.endswith(".html") else "application/octet-stream"
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)
        except Exception:
            self.send_error(404)

    def do_GET(self):
        p = urlparse(self.path).path
        if p == "/api/status":
            return self._json(api_status())
        if p == "/api/modes":
            return self._json(api_modes())
        if p == "/api/governors":
            return self._json(api_governors())
        if p == "/" or p == "/index.html":
            return self._file(os.path.join(STATIC, "index.html"))
        if p.startswith("/static/"):
            return self._file(os.path.join(STATIC, os.path.basename(p)))
        self.send_error(404)

    def do_POST(self):
        try:
            n = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(n) or b"{}")
        except Exception:
            return self._json({"ok": False}, 400)
        p = urlparse(self.path).path
        if p == "/api/cells":
            return self._json(api_save_cells(body.get("cellMode")))
        if p == "/api/mode":
            return self._json(api_save_mode(body.get("key"), body.get("mode")))
        if p == "/api/governor":
            return self._json(api_save_governor(body))
        self._json({"ok": False}, 404)

if __name__ == "__main__":
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print(f"Color管理器 WebUI 已启动（仅本机可访问）")
    print(f"  浏览器打开: http://127.0.0.1:{PORT}")
    print(f"  演示模式: {'开(未检测到sysfs)' if read_int(BAT + '/capacity') is None else '关(真实数据)'}")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止")
