<template>
  <section>
    <div v-if="loading" class="unavail">正在加载…</div>
    <div v-else-if="unavail" class="unavail"><b>{{ unavail.t }}</b><br>{{ unavail.s }}</div>
    <div v-else>
      <div class="chips">
        <button v-for="(k, i) in MODE_KEYS" :key="k" class="chip" :class="{ on: k === editMode }"
          :style="{ borderColor: k === editMode ? MODE_COLORS[i] : '' }" @click="editMode = k">
          <span class="dot" :style="{ background: MODE_COLORS[i] }"></span>{{ MODE_NAMES[k] }}
        </button>
      </div>
      <div class="desc">{{ MODE_DESC[MODE_KEYS.indexOf(editMode)] }}</div>
      <div class="card">
        <div v-for="d in activeSliders" :key="d.k" class="sl">
          <label>{{ d.t }}</label>
          <input type="range" :min="d.min" :max="d.max" :step="d.step" :value="numVal(d.k)"
            :style="{ '--p': pct(d) + '%' }" @input="onSlide(d, $event)">
          <output>{{ dispVal(d) }}</output>
        </div>
      </div>
      <button class="btn" @click="saveMode">保存</button>
      <div class="hint" style="text-align:center;margin-top:8px">{{ fileHint }}</div>
    </div>
  </section>
</template>

<script setup>
/**
 * 调度参数页：四模式（省电/均衡/性能/极速）CPU 频率、提升、uclamp、WALT 参数编辑。
 * 移植自 WebUI（magisk-module/webroot/index.html 调度参数 section）：
 * 配置文件 = /data/adb/modules/colorFC/config/<方案>.<peiz>.sh（平台专属优先，缺失回退 all），
 * 保存采用三方合并（fresh=磁盘最新 / edited=当前 UI / initial=打开时快照）防止双端互相覆盖。
 */
import { ref, computed, onMounted, onActivated } from 'vue'
import { exec, readFile, writeFile, rootState } from '../bridge'
import { showToast } from '../ui'

/* ============ 常量（与 WebUI 蓝本一致） ============ */
const MOD = '/data/adb/modules/colorFC'
const MODE_KEYS = ['powersave', 'balance', 'performance', 'fast']
const MODE_NAMES = { powersave: '省电', balance: '均衡', performance: '性能', fast: '极速' }
const MODE_COLORS = ['#00B5A3', '#0096C8', '#E08A00', '#A02CF0']
const MODE_DESC = ['powersave · 最长续航，压制频率与提升值', 'balance · 日常使用，兼顾流畅与功耗',
  'performance · 高负载场景，激进提升', 'fast · 极限性能，全力释放']

const SLIDER_DEFS = [
  { k: 'opt2',         t: '性能提升',   min: 0,  max: 100,   step: 1,     suf: '%' },
  { k: 'cpuMax',       t: 'CPU 上限',  min: 5,  max: 100,   step: 1,     suf: '%' },
  { k: 'cpuMaxB',      t: '大核上限',   min: 5,  max: 100,   step: 1,     suf: '%' },
  { k: 'cpuMin',       t: 'CPU 下限',  min: 0,  max: 95,    step: 1,     suf: '%' },
  { k: 'llcc',         t: 'LLCC 频率', min: 350000, max: 1800000, step: 50000, div: 1000, suf: ' MHz' },
  { k: 'uclampDisplay', t: '显示增强',  min: 0, max: 100, step: 1, suf: '%' },
  { k: 'uclampSsfg',   t: '后台增强',   min: 0, max: 100, step: 1, suf: '%' },
  { k: 'uclampTouch',  t: '触控增强',   min: 0, max: 100, step: 1, suf: '%' },
  { k: 'uclampMm',     t: '多媒体增强', min: 0, max: 100, step: 1, suf: '%' },
  { k: 'uclampRt',     t: '实时增强',   min: 0, max: 100, step: 1, suf: '%' },
  { k: 'uclampTopApp', t: '前台增强',   min: 0, max: 100, step: 1, suf: '%' },
  { k: 'walt1', t: 'WALT 限制1', min: 0, max: 100,   step: 1,   suf: '', only: 'fast' },
  { k: 'walt2', t: 'WALT 限制2', min: 100, max: 10000, step: 100, suf: ' µs', only: 'fast' },
]

/* 形态/模块/当前模式一次探测（对齐蓝本 detectForm + refreshHome 的 CUR 读取） */
const PROBE =
  'S=$(cat /data/adb/colorFC_store/state 2>/dev/null);' +
  'echo "MOD=$(test -d ' + MOD + ' && echo 1 || echo 0)";' +
  '[ -f ' + MOD + '/powerd.sh ] && echo "PD=1";' +
  '[ -d ' + MOD + '/script ] && echo "SC=1";' +
  'echo "STATE=$S";' +
  'echo "CUR=$(cat /sdcard/Android/qingtd/cur_powermode.txt 2>/dev/null)"'

/* ============ 响应式状态 ============ */
const loading = ref(true)
const unavail = ref(null)           // {t, s} 不可用提示
const editMode = ref('powersave')   // 当前编辑的模式
const modesData = ref({})           // 解析后的四模式参数
const activeConf = ref('')          // 活动配置文件（config/a.all.sh …）

/* 非渲染状态 */
let modesInitial = {}               // 打开页面时的快照（保存时三方合并用）
let confText = ''                   // 活动配置文件内容
let curMode = ''                    // 当前运行模式
let activeScheme = 'a'              // A/B/C 方案
let busy = false
let saving = false

const activeSliders = computed(() => SLIDER_DEFS.filter(d => !d.only || d.only === editMode.value))
const fileHint = computed(() => (activeConf.value ? '配置文件：' + activeConf.value : ''))

/* ============ 滑条取值/显示 ============ */
function numVal(k) {
  const md = modesData.value[editMode.value]
  const v = md ? md[k] : 0
  return (v === '' || v == null) ? 0 : +v
}
function dispVal(d) {
  const v = numVal(d.k)
  return (d.div ? v / d.div : v) + d.suf
}
function pct(d) {
  const v = numVal(d.k)
  return (v - d.min) / (d.max - d.min) * 100
}
function onSlide(d, ev) {
  const md = modesData.value[editMode.value]
  if (md) md[d.k] = +ev.target.value
}

/* ============ 桥工具 ============ */
async function shOut(cmd, timeoutSec) {
  const r = await exec(cmd, timeoutSec)
  return ((r && r.out) || '').trim()
}
/* readFile（原生 root 读）优先，失败走 exec cat 兜底（对齐蓝本 base64→cat 降级思路） */
async function readText(path) {
  let t = null
  try { t = await readFile(path) } catch (e) { t = null }
  if (t) return t
  try {
    const r = await exec('cat "' + path + '" 2>/dev/null', 10)
    return (r && r.ok) ? r.out : ''
  } catch (e) { return '' }
}

/* ============ 方案检测（统一走 script/fangan.sh，与 main.sh 一致） ============ */
async function detectActiveConf() {
  const P = ((await shOut('cat ' + MOD + '/files/peiz 2>/dev/null')) || 'all').trim() || 'all'
  const out = await shOut('sh ' + MOD + '/script/fangan.sh 2>/dev/null', 15)
  const m = out.match(/^([abc])\s+(\S+)/)
  activeScheme = m ? m[1] : 'a'
  // 平台专属配置优先，缺失回退 all（与 main.sh 加载规则一致）
  const has = await shOut('test -f ' + MOD + '/config/' + activeScheme + '.' + P + '.sh && echo 1 || echo 0')
  activeConf.value = (has === '1')
    ? 'config/' + activeScheme + '.' + P + '.sh'
    : 'config/' + activeScheme + '.all.sh'
}

/* ============ 配置解析/构建（蓝本原样移植） ============ */
function parseConf(text) {
  const data = {}
  MODE_KEYS.forEach(mode => {
    const re = new RegExp('if \\[\\[ \\$action == "' + mode + '" \\]\\]; then([\\s\\S]*?)\\nfi', 'm')
    const bm = text.match(re)
    const blk = bm ? bm[1] : ''
    const d = {}
    d.opt2 = +(blk.match(/\$mokzdz\/[ABC]\/opt2 (\d+)/) || [])[1] || 0
    // 2 参=全簇同上限，4 参=小核上限/大核上限/下限/GPU
    const jm = blk.match(/json_cpu_max_min "(\d+)" "(\d+)"(?: "(\d+)")?(?: "(\d+)")?/)
    if (jm && jm[3] != null) {
      d.cpuMax = +jm[1]; d.cpuMaxB = +jm[2]; d.cpuMin = +jm[3]
    } else if (jm) {
      d.cpuMax = +jm[1]; d.cpuMaxB = +jm[1]; d.cpuMin = +jm[2]
    } else { d.cpuMax = 100; d.cpuMaxB = 100; d.cpuMin = 0 }
    d.llcc = +(blk.match(/llcc\.sh set_max_freq (\d+)/) || [])[1] || 0
    const um = {}
    blk.replace(/echo "(\d+)"\s*>\s*\/dev\/cpuctl\/(display|ssfg|touch|multimedia|rt|top-app)\/cpu\.uclamp\.min/g,
      (_, v, g) => { um[g] = +v; return _ })
    d.uclampDisplay = um.display ?? 0; d.uclampSsfg = um.ssfg ?? 0; d.uclampTouch = um.touch ?? 0
    d.uclampMm = um.multimedia ?? 0; d.uclampRt = um.rt ?? 0; d.uclampTopApp = um['top-app'] ?? 0
    const wm = blk.match(/walt_up_rate_limit_us "(\d+)" "(\d+)"/)
    d.walt1 = wm ? +wm[1] : 0; d.walt2 = wm ? +wm[2] : 1500
    data[mode] = d
  })
  return data
}

/* 在原文上按模式块替换参数（保留其余内容不动） */
function buildConf(text, data) {
  let out = text
  MODE_KEYS.forEach(mode => {
    const re = new RegExp('if \\[\\[ \\$action == "' + mode + '" \\]\\]; then([\\s\\S]*?)\\nfi', 'm')
    const bm = out.match(re)
    if (!bm) return
    let blk = bm[1]
    const d = data[mode]
    blk = blk.replace(/(\$mokzdz\/[ABC]\/opt2 )\d+/, '$1' + d.opt2)
    blk = blk.replace(/json_cpu_max_min "[^"]*" "[^"]*"(?: "[^"]*")?(?: "[^"]*")?/,
      'json_cpu_max_min "' + d.cpuMax + '" "' + d.cpuMaxB + '" "' + d.cpuMin + '" "0"')
    blk = blk.replace(/(llcc\.sh set_max_freq )\d+/, '$1' + d.llcc)
    blk = blk.replace(/echo "(\d+)"(\s*>\s*\/dev\/cpuctl\/display\/cpu\.uclamp\.min)/, 'echo "' + d.uclampDisplay + '"$2')
    blk = blk.replace(/echo "(\d+)"(\s*>\s*\/dev\/cpuctl\/ssfg\/cpu\.uclamp\.min)/, 'echo "' + d.uclampSsfg + '"$2')
    blk = blk.replace(/echo "(\d+)"(\s*>\s*\/dev\/cpuctl\/touch\/cpu\.uclamp\.min)/, 'echo "' + d.uclampTouch + '"$2')
    blk = blk.replace(/echo "(\d+)"(\s*>\s*\/dev\/cpuctl\/multimedia\/cpu\.uclamp\.min)/, 'echo "' + d.uclampMm + '"$2')
    blk = blk.replace(/echo "(\d+)"(\s*>\s*\/dev\/cpuctl\/rt\/cpu\.uclamp\.min)/, 'echo "' + d.uclampRt + '"$2')
    blk = blk.replace(/echo "(\d+)"(\s*>\s*\/dev\/cpuctl\/top-app\/cpu\.uclamp\.min)/, 'echo "' + d.uclampTopApp + '"$2')
    if (mode === 'fast') {
      blk = blk.replace(/walt_up_rate_limit_us "\d+" "\d+"/, 'walt_up_rate_limit_us "' + d.walt1 + '" "' + d.walt2 + '"')
    }
    out = out.replace(re, 'if [[ $action == "' + mode + '" ]]; then' + blk + '\nfi')
  })
  return out
}

/* 三方合并（防双端互相覆盖）：fresh=磁盘最新；edited=当前 UI（含用户编辑）；
   initial=页面打开时的快照。仅用户真正动过的字段（edited!=initial）用 UI 值，其余以磁盘为准 */
function mergeModeData(fresh, edited, initial) {
  const out = {}
  MODE_KEYS.forEach(mode => {
    const f = fresh[mode] || {}, e = edited[mode] || {}, i0 = initial[mode] || {}
    const o = {}
    Object.keys(e).forEach(k => {
      o[k] = (e[k] !== i0[k]) ? e[k] : (f[k] !== undefined ? f[k] : e[k])
    })
    Object.keys(f).forEach(k => { if (o[k] === undefined) o[k] = f[k] })
    out[mode] = o
  })
  return out
}

/* ============ 初始化 / 刷新 ============ */
async function init() {
  if (busy) return
  busy = true
  loading.value = true
  unavail.value = null
  try {
    let rs = null
    try { rs = await rootState() } catch (e) { rs = null }
    if (rs && rs.rooted === false) {
      unavail.value = { t: 'Root 不可用', s: '请先授予 Root 权限后重试' }
      return
    }
    let r = null
    try { r = await exec(PROBE, 8) } catch (e) { r = null }
    if (!r || !r.ok) {
      unavail.value = { t: 'Root 不可用', s: '请先授予 Root 权限后重试' }
      return
    }
    const out = r.out || ''
    const modOk = (out.match(/^MOD=(.*)$/m) || [])[1] === '1'
    if (!modOk) {
      unavail.value = { t: '未检测到 colorFC 模块', s: '请先安装模块后重试' }
      return
    }
    const hasPd = /^PD=1$/m.test(out), hasSc = /^SC=1$/m.test(out)
    const st = (out.match(/^STATE=(.*)$/m) || [])[1] || ''
    const form = hasPd && !hasSc ? 'adapt' : (hasSc && !hasPd ? 'dispatch' : (st === 'adapt' ? 'adapt' : 'dispatch'))
    if (form !== 'dispatch') {
      unavail.value = { t: '自适应限频形态下不可用', s: '请先在主页切回 Color 调度形态' }
      return
    }
    curMode = ((out.match(/^CUR=(.*)$/m) || [])[1] || '').trim()
    if (!MODE_KEYS.includes(curMode)) curMode = 'balance'
    if (MODE_KEYS.includes(curMode)) editMode.value = curMode

    await detectActiveConf()
    confText = await readText(MOD + '/' + activeConf.value)
    if (!confText) showToast('读取 ' + activeConf.value + ' 失败，当前显示默认值')
    modesData.value = parseConf(confText)
    modesInitial = JSON.parse(JSON.stringify(modesData.value))
  } catch (e) {
    unavail.value = { t: '初始化失败', s: '请稍后重试' }
  } finally {
    busy = false
    loading.value = false
  }
}

/* ============ 保存（三方合并 + 实时应用） ============ */
async function saveMode() {
  if (saving) return
  if (!confText) { showToast('配置读取失败，已阻止保存（防止清空脚本）'); return }
  saving = true
  try {
    // 保存前重读磁盘最新内容再合并：防止页面持有的旧快照把 APP/另一端的改动覆盖回去
    const freshText = await readText(MOD + '/' + activeConf.value)
    let text
    if (freshText) {
      const merged = mergeModeData(parseConf(freshText), modesData.value, modesInitial)
      text = buildConf(freshText, merged)
      modesData.value = merged
      modesInitial = JSON.parse(JSON.stringify(merged))
    } else {
      text = buildConf(confText, modesData.value)
    }
    const r = await writeFile(text, MOD + '/' + activeConf.value)
    if (!r || !r.ok) { showToast('写入失败'); return }
    confText = text
    let extra = ''
    if (editMode.value === curMode) {
      // 统一调度接口（kzlx=1 内部语义：仅应用，不改 moren、不停动态切换）
      await exec('sh /data/powercfg.sh ' + editMode.value + ' 1', 20)
      extra = '，已实时应用'
    }
    showToast('已保存' + extra)
  } catch (e) {
    showToast('保存失败')
  } finally {
    saving = false
  }
}

onMounted(() => { init() })
/* keep-alive：页面重新可见时重读磁盘与当前模式（对齐蓝本 go('modes') 行为） */
onActivated(() => { init() })
</script>
