/**
 * 原生桥：全部调用异步（Promise），绝不阻塞 JS 线程。
 * 原生侧单线程队列执行 su/文件操作，完成后经 evaluateJavascript 回调。
 */

let seq = 0
const pending = new Map()

window.__cfResult = function (id, ok, json) {
  const p = pending.get(id)
  if (!p) return
  pending.delete(id)
  if (ok) {
    let v = json
    try { v = JSON.parse(json) } catch (e) { /* 原样返回 */ }
    p.res(v)
  } else {
    p.rej(new Error(typeof json === 'string' ? json : 'bridge error'))
  }
}

const evtListeners = []
window.__cfEvent = function (json) {
  let ev = null
  try { ev = JSON.parse(json) } catch (e) { return }
  for (const f of evtListeners) {
    try { f(ev) } catch (e) { /* 单个监听异常不影响其它 */ }
  }
}

/** 订阅原生事件：{type:'bgChanged'|'rootChanged'} */
export function onEvent(fn) { evtListeners.push(fn) }

/** 底层调用：call('exec', cmd, timeoutSec) */
export function call(method, ...args) {
  return new Promise((resolve, reject) => {
    const id = ++seq
    pending.set(id, { res: resolve, rej: reject })
    try {
      window.NativeFC.call(method, JSON.stringify(args), id)
    } catch (e) {
      pending.delete(id)
      reject(e)
    }
  })
}

// ==================== Shell / Root ====================

/** su -c 执行 → {code, out, err, ok} */
export function exec(cmd, timeoutSec) {
  return call('exec', cmd, timeoutSec || 6).then(r => r || { code: -1, out: '', err: 'no result', ok: false })
}

/** root 读文件 → 文本 | null */
export function readFile(path) { return call('readFile', path) }

/** root 写文件（经缓存文件 cp + chmod 755）→ {ok} */
export function writeFile(content, target) { return call('writeFile', content, target) }

/** root 批量写：[[content, target], ...] → {ok} */
export function writeFiles(pairs) { return call('writeFiles', pairs) }

/** 系统属性 → string */
export function getprop(name) { return call('getprop', name) }

/** 是否存在 su 二进制（不弹授权窗） */
export function hasSuBinary() { return call('hasSuBinary') }

/** Root 状态：{rooted, checked, requesting} */
export function rootState() { return call('rootState') }

/** 发起 Root 授权申请（原生弹窗流程） */
export function requestRoot() { return call('requestRoot') }

// ==================== 电池 / 功耗 ====================

/** 读电池状态 → {watts,volts,amps,cells,level,tempC,status} | null */
export function readBattery() { return call('readBattery') }

/** 按电芯模式修正功耗（带符号：充电正 / 放电负）→ number */
export function applyCellMode(stat, cellMode) { return call('applyCellMode', stat, cellMode) }

export function getCellMode() { return call('getCellMode') }
export function setCellMode(v) { return call('setCellMode', v) }

/** 近 N 分钟功耗采样 → number[]（时间正序） */
export function recentWatts(minutes) { return call('recentWatts', minutes) }

/** 本机功耗采样 CSV 全文（时间,功率,电压,温度,电量,充放状态） */
export function powerCsv() { return call('powerCsv') }

/** 清空本机功耗采样 */
export function clearPowerCsv() { return call('clearPowerCsv') }

// ==================== CPU ====================

/** 全核快照 → {cores, online:[bool], freqMhz:[int], busy:[float], onlineCount} */
export function cpuSnapshot() { return call('cpuSnapshot') }

/** 簇拓扑 → [[policy, firstCpu, lastCpu], ...] */
export function cpuTopology() { return call('cpuTopology') }

/** 即时开关核心 → bool */
export function setCoreOnline(cpu, on) { return call('setCoreOnline', cpu, on) }

// ==================== 悬浮窗 ====================

/** 四个监视窗开关状态 → [bool, bool, bool, bool]（负载/进程/迷你/温度） */
export function overlayStates() { return call('overlayStates') }

/** 开关监视窗 → {ok, needPermission} */
export function overlaySet(type, on) { return call('overlaySet', type, on) }

export function canDrawOverlays() { return call('canDrawOverlays') }
export function requestOverlayPermission() { return call('requestOverlayPermission') }

// ==================== 主题 ====================

/** → {dark, transparent, imageBg, bgAlpha, bgScale, bgOffX, bgOffY, glass, liquid, hasImage} */
export function themeGet() { return call('themeGet') }

/** 写主题项（原生同步到窗口/系统栏） */
export function themeSet(key, value) { return call('themeSet', key, value) }

/** 背景图（预缩放 JPEG dataURL）| null */
export function bgImage() { return call('bgImage') }

/** 打开系统图片选择器（结果经 bgChanged 事件通知） */
export function pickImage() { return call('pickImage') }

// ==================== 其它 ====================

/** 可启动应用列表 → [{pkg, label}] */
export function appList() { return call('appList') }

/** 确保单应用限频执行服务在跑 */
export function appLimitEnsure() { return call('appLimitEnsure') }

/** SOC 信息 → {code, marketing, shortName, vendor, config, known} */
export function soc() { return call('soc') }

/** Toast（原生，与系统风格一致） */
export function toast(msg) { return call('toast', msg) }
