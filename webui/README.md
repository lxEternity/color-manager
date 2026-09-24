# Color管理器 WebUI

APP 的网页版（无密码锁），功能对齐 Android 版。**纯本地工作，无需联网**：服务器仅绑定 127.0.0.1，局域网其他设备无法访问。

## 功能
- **主页**：SOC 环形电量、实时功耗大字（双电芯自动 ×2）、电压/电流/温度/循环、电芯检测（自动识别 / 单电芯 / 双电芯，选择持久保存）、功耗实时曲线
- **调度参数**：省电 / 均衡 / 性能 / 极速 四模式，滑条编辑（性能提升、CPU 上下限、LLCC 频率、六大 uclamp 增强、极速模式 WALT 参数）
- **调速器配置**：9 个预设调速器切换（conservative、walt、ips、sugov_next、scx、hmbird、powersave、performance、schedutil），conservative/scx 附加参数滑条，保存后直接写入 sysfs

## 部署

### 安卓手机（Termux，真实数据）
```bash
pkg install python
# 需要 root 读取电池/切换调速器：
tsu
cd webui && python server.py
```
手机浏览器打开 `http://127.0.0.1:8765`（仅手机本机可访问）。

### 电脑（演示模式）
```bash
python server.py
```
读不到 sysfs 时自动进入**演示模式**（橙色徽标提示），UI 与参数保存全部可用，数据为模拟。

## 说明
- 电芯判定：电压 ≥ 4.30V 判为双电芯（串联电芯 4.4xV 特征），功耗自动 ×2
- 参数持久化在 `data.json`，重启不丢失
- 切换调速器需要 root；无 root 时保存参数并提示"需要 root"
