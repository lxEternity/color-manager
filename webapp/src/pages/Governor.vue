<template>
  <section>
    <div v-if="loading" class="unavail">正在加载…</div>
    <div v-else-if="unavail" class="unavail"><b>{{ unavail.t }}</b><br>{{ unavail.s }}</div>
    <div v-else>
      <div class="chips">
        <button v-for="(k, i) in MODE_KEYS" :key="k" class="chip" :class="{ on: k === govMode }"
          :style="{ borderColor: k === govMode ? MODE_COLORS[i] : '' }" @click="switchMode(k)">
          <span class="dot" :style="{ background: MODE_COLORS[i] }"></span>{{ MODE_NAMES[k] }}
        </button>
      </div>
      <div class="desc">{{ MODE_DESC[MODE_KEYS.indexOf(govMode)] }}</div>
      <div class="desc" style="color:var(--ac);font-weight:600;margin:-4px 2px 11px">{{ socTip }}</div>
      <div class="card row-between">
        <div>
          <small style="display:block;color:var(--t3);font-size:11px">当前调速器</small>
          <b style="font-size:19px;color:var(--ac)">{{ govNow || '--' }}</b>
        </div>
        <div class="dd" :class="{ open: ddOpen }">
          <button class="dd-btn" @click.stop="ddOpen = !ddOpen">
            <span>{{ govData.governor || '切换调速器' }}</span>
            <svg class="arr" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><path d="M6 9l6 6 6-6" /></svg>
          </button>
          <div class="dd-list">
            <div v-for="p in govAvail" :key="p" class="it" :class="{ on: p === govData.governor }" @click="pickGov(p)">{{ p }}</div>
          </div>
        </div>
      </div>
      <div class="card">
        <template v-if="govDefs">
          <template v-for="g in govGroups" :key="g.sfx || 'small'">
            <div v-if="g.name" class="group" style="font-weight:600;margin:10px 2px 2px">{{ g.name }}</div>
            <div v-for="d in govDefs" :key="d.k + g.sfx" class="sl">
              <label>{{ d.t }}{{ g.short ? '·' + g.short : '' }}</label>
              <input type="range" :min="d.min" :max="d.max" :step="d.step" :value="numVal(d.k + g.sfx)"
                :style="{ '--p': pct(d, g.sfx) + '%' }" @input="onSlide(d, g.sfx, $event)">
              <output>{{ dispVal(d, g.sfx) }}</output>
            </div>
          </template>
        </template>
        <div v-else class="hint" style="margin:4px 2px">该调速器无附加参数，保存即切换生效</div>
      </div>
      <button class="btn" @click="saveGov">保存</button>
    </div>
  </section>
</template>

<script setup>
/**
 * 调速器页：当前调速器显示（sysfs 实时值）、本机可用调速器扫描（下拉切换）、
 * conservative / scx / walt 参数解析与编辑（C 方案大小核独立 → 双组滑条）。
 * 移植自 WebUI（magisk-module/webroot/index.html 调速器 section）：
 * 脚本 = /data/adb/modules/colorFC/<A|B|C>/<模式>.sh，保存前重读磁盘做三方合并防双端覆盖。
 */
import { ref, computed, onMounted, onActivated, onDeactivated, onBeforeUnmount } from 'vue'
import { exec, readFile, writeFile, rootState, soc } from '../bridge'
import { showToast } from '../ui'

/* ============ 常量（与 WebUI 蓝本一致） ============ */
const MOD = '/data/adb/modules/colorFC'
const MODE_KEYS = ['powersave', 'balance', 'performance', 'fast']
const MODE_NAMES = { powersave: '省电', balance: '均衡', performance: '性能', fast: '极速' }
const MODE_COLORS = ['#00B5A3', '#0096C8', '#E08A00', '#A02CF0']
const MODE_DESC = ['powersave · 最长续航，压制频率与提升值', 'balance · 日常使用，兼顾流畅与功耗',
  'performance · 高负载场景，激进提升', 'fast · 极限性能，全力释放']
const GOV_FILES = { powersave: 'conservative.sh', balance: 'scx1.sh', performance: 'scx2.sh', fast: 'scx3.sh' }

const PRESETS = ['conservative', 'walt', 'ips', 'sugov_next', 'scx', 'hmbird', 'powersave', 'performance', 'schedutil']
const GOV_SLIDERS = {
  conservative: [
    { k: 'upThreshold', t: '升频阈值', min: 1, max: 100, step: 1, suf: '%' },
    { k: 'downThreshold', t: '降频阈值', min: 1, max: 100, step: 1, suf: '%' },
    { k: 'freqStep', t: '调频步进', min: 1, max: 100, step: 1, suf: '%' },
    { k: 'samplingRate', t: '采样周期', min: 1000, max: 200000, step: 1000, div: 1000, suf: ' ms' },
  ],
  scx: [{ k: 'targetLoads', t: '目标负载', min: 1, max: 100, step: 1, suf: '%' }],
  walt: [{ k: 'targetLoads', t: '目标负载', min: 1, max: 100, step: 1, suf: '%' }],
}

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
const unavail = ref(null)          // {t, s} 不可用提示
const govMode = ref('powersave')   // 选中的模式
const govData = ref({
  governor: '', upThreshold: '', downThreshold: '', freqStep: '', samplingRate: '', targetLoads: '',
  upThresholdBig: '', downThresholdBig: '', freqStepBig: '', samplingRateBig: '', targetLoadsBig: '', hasBig: false,
})
const govAvail = ref([])           // 本机可用调速器（sysfs 过滤）
const govNow = ref('')             // 当前运行调速器（sysfs 实时值）
const socTip = ref('--')
const ddOpen = ref(false)

/* 非渲染状态 */
let govInitial = null              // 打开页面时的快照（保存时三方合并用）
let curMode = ''                   // 当前运行模式
let activeScheme = 'a'             // A/B/C 方案
let socInfo = null                 // {code, marketing, known}（仅用于 SOC 提示展示）
let busy = false
let saving = false

/* ============ 滑条定义 / 分组 ============ */
/* 当前调速器对应的滑条定义：
   walt 特殊处理——C方案(walt/target_loads)→目标负载；A方案极速(walt+conservative参数)→conservative 组 */
function sliderDefsFor(d) {
  if (d.governor === 'walt') {
    return (d.targetLoads || d.targetLoadsBig) ? GOV_SLIDERS.walt : GOV_SLIDERS.conservative
  }
  return GOV_SLIDERS[d.governor]
}
const govDefs = computed(() => sliderDefsFor(govData.value))
/* C方案大小核参数独立（hasBig）→ 双组滑条：小核 cpu0-3 / 大核 cpu4-7 */
const govGroups = computed(() => govData.value.hasBig
  ? [{ sfx: '', name: '小核·cpu0-3', short: '小核' }, { sfx: 'Big', name: '大核·cpu4-7', short: '大核' }]
  : [{ sfx: '', name: '', short: '' }])

function numVal(key) {
  const v = govData.value[key]
  return (v === '' || v == null) ? 0 : +v
}
function dispVal(d, sfx) {
  const v = numVal(d.k + sfx)
  return (d.div ? v / d.div : v) + d.suf
}
function pct(d, sfx) {
  const v = numVal(d.k + sfx)
  return (v - d.min) / (d.max - d.min) * 100
}
function onSlide(d, sfx, ev) {
  govData.value[d.k + sfx] = +ev.target.value
}

/* ============ 下拉选择器 ============ */
function pickGov(p) {
  govData.value.governor = p
  ddOpen.value = false
}
function onDocClick() { ddOpen.value = false }

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

/* ============ SOC / 方案 / 实时调速器 ============ */
async function detectSoc() {
  if (socInfo) return socInfo
  try {
    const s = await soc()
    if (s && s.code) {
      socInfo = { code: s.code, marketing: s.marketing || '未知', known: !!s.known }
      return socInfo
    }
  } catch (e) { /* 原生 SOC 检测失败时按未知处理 */ }
  socInfo = { code: '--', marketing: '未知', known: false }
  return socInfo
}
/* 方案检测统一走 script/fangan.sh（与 main.sh / install.sh 同一实现）：
   scx→A / hmbird→B / sugov_next→A / 三者都没有→C */
async function detectActiveConf() {
  const out = await shOut('sh ' + MOD + '/script/fangan.sh 2>/dev/null', 15)
  const m = out.match(/^([abc])\s+(\S+)/)
  activeScheme = m ? m[1] : 'a'
}
/* 方案对应的调速器名：activeScheme 由 fangan.sh 按调速器可用性判定（与 main.sh 一致） */
function schemeGovName() { return activeScheme === 'b' ? 'conservative' : activeScheme === 'c' ? 'walt' : 'scx' }
/* 当前运行调速器：读 sysfs 实时值。不能按方案映射显示——方案 A 下省电实际是
   conservative、极速是 walt，按 scheme 映射会恒显 scx */
async function readLiveGov() {
  const g = await shOut('cat /sys/devices/system/cpu/cpufreq/policy0/scaling_governor 2>/dev/null')
  return /^[\w.-]+$/.test(g) ? g : ''
}
async function refreshGovNow() {
  const live = await readLiveGov()
  govNow.value = live || schemeGovName()
}

/* ============ 调速器脚本解析/构建（蓝本原样移植） ============ */
/* 分组解析：小核=cpu0-3 首个值，大核=cpu4-7 首个值（C方案大小核参数独立） */
function govGrpVal(text, rx) {
  rx.lastIndex = 0
  let small = null, big = null, m
  while ((m = rx.exec(text))) {
    const cpu = +m[2], v = m[1]
    if (cpu <= 3 && small === null) small = v
    else if (cpu >= 4 && big === null) big = v
  }
  return [small || '', big || '']
}

function parseGovScript(text) {
  const d = { governor: '', upThreshold: '', downThreshold: '', freqStep: '', samplingRate: '', targetLoads: '',
    upThresholdBig: '', downThresholdBig: '', freqStepBig: '', samplingRateBig: '', targetLoadsBig: '', hasBig: false }
  d.governor = (text.match(/echo "?([\w.-]+)"? > \S*scaling_governor/) || [])[1] || ''
  ;[d.upThreshold, d.upThresholdBig] = govGrpVal(text, /echo "?([\w.-]+)"? > \S*cpu([0-7])\/cpufreq\/conservative\/up_threshold/g)
  ;[d.downThreshold, d.downThresholdBig] = govGrpVal(text, /echo "?([\w.-]+)"? > \S*cpu([0-7])\/cpufreq\/conservative\/down_threshold/g)
  ;[d.freqStep, d.freqStepBig] = govGrpVal(text, /echo "?([\w.-]+)"? > \S*cpu([0-7])\/cpufreq\/conservative\/freq_step/g)
  ;[d.samplingRate, d.samplingRateBig] = govGrpVal(text, /echo "?([\w.-]+)"? > \S*cpu([0-7])\/cpufreq\/conservative\/sampling_rate/g)
  ;[d.targetLoads, d.targetLoadsBig] = govGrpVal(text, /echo "?([\w.-]+)"? > \S*cpu([0-7])\/cpufreq\/(?:scx|walt)\/target_loads/g)
  // 兜底：无 cpu 编号的路径（与旧脚本兼容）
  if (!d.upThreshold) d.upThreshold = (text.match(/echo "?([\w.-]+)"? > \S*conservative\/up_threshold/) || [])[1] || ''
  if (!d.downThreshold) d.downThreshold = (text.match(/echo "?([\w.-]+)"? > \S*conservative\/down_threshold/) || [])[1] || ''
  if (!d.freqStep) d.freqStep = (text.match(/echo "?([\w.-]+)"? > \S*conservative\/freq_step/) || [])[1] || ''
  if (!d.samplingRate) d.samplingRate = (text.match(/echo "?([\w.-]+)"? > \S*conservative\/sampling_rate/) || [])[1] || ''
  if (!d.targetLoads) d.targetLoads = (text.match(/echo "?([\w.-]+)"? > \S*(?:scx|walt)\/target_loads/) || [])[1] || ''
  // 大核存在且与小核取值不同 → 双组滑条
  const sm = [d.upThreshold, d.downThreshold, d.freqStep, d.samplingRate, d.targetLoads]
  const bg = [d.upThresholdBig, d.downThresholdBig, d.freqStepBig, d.samplingRateBig, d.targetLoadsBig]
  d.hasBig = bg.some((v, i) => v && v !== sm[i])
  return d
}

function buildGovScript(text, d) {
  let out = text
  // 空值不回写，避免生成 "echo > path" 的坏脚本
  const rep = (rx, v) => { if (v !== '' && v != null) out = out.replace(rx, '$1' + v + '$2') }
  rep(/(echo "?)[\w.-]+("? > \S*scaling_governor)/g, d.governor)
  // 1) 兜底：先按统一值替换（含 cpu$cpu 循环变量的脚本）；之后大小组再覆盖
  rep(/(echo "?)[\w.-]+("? > \S*conservative\/up_threshold)/g, d.upThreshold)
  rep(/(echo "?)[\w.-]+("? > \S*conservative\/down_threshold)/g, d.downThreshold)
  rep(/(echo "?)[\w.-]+("? > \S*conservative\/freq_step)/g, d.freqStep)
  rep(/(echo "?)[\w.-]+("? > \S*conservative\/sampling_rate)/g, d.samplingRate)
  rep(/(echo "?)[\w.-]+("? > \S*(?:scx|walt)\/target_loads)/g, d.targetLoads)
  // 2) 小核 cpu0-3
  rep(/(echo "?)[\w.-]+("? > \S*cpu[0-3]\/cpufreq\/conservative\/up_threshold)/g, d.upThreshold)
  rep(/(echo "?)[\w.-]+("? > \S*cpu[0-3]\/cpufreq\/conservative\/down_threshold)/g, d.downThreshold)
  rep(/(echo "?)[\w.-]+("? > \S*cpu[0-3]\/cpufreq\/conservative\/freq_step)/g, d.freqStep)
  rep(/(echo "?)[\w.-]+("? > \S*cpu[0-3]\/cpufreq\/conservative\/sampling_rate)/g, d.samplingRate)
  rep(/(echo "?)[\w.-]+("? > \S*cpu[0-3]\/cpufreq\/(?:scx|walt)\/target_loads)/g, d.targetLoads)
  // 3) 大核 cpu4-7（未区分大小核时与小核同值）
  rep(/(echo "?)[\w.-]+("? > \S*cpu[4-7]\/cpufreq\/conservative\/up_threshold)/g, d.upThresholdBig || d.upThreshold)
  rep(/(echo "?)[\w.-]+("? > \S*cpu[4-7]\/cpufreq\/conservative\/down_threshold)/g, d.downThresholdBig || d.downThreshold)
  rep(/(echo "?)[\w.-]+("? > \S*cpu[4-7]\/cpufreq\/conservative\/freq_step)/g, d.freqStepBig || d.freqStep)
  rep(/(echo "?)[\w.-]+("? > \S*cpu[4-7]\/cpufreq\/conservative\/sampling_rate)/g, d.samplingRateBig || d.samplingRate)
  rep(/(echo "?)[\w.-]+("? > \S*cpu[4-7]\/cpufreq\/(?:scx|walt)\/target_loads)/g, d.targetLoadsBig || d.targetLoads)
  return out
}

/* 三方合并（调速器页，防双端互相覆盖）：fresh=磁盘最新；edited=当前 UI；initial=快照。
   仅用户动过的字段用 UI 值，其余以磁盘为准（保留 APP/另一端的改动） */
function mergeGovData(fresh, edited, initial) {
  const o = Object.assign({}, fresh)
  Object.keys(edited).forEach(k => {
    o[k] = (edited[k] !== initial[k]) ? edited[k] : (fresh[k] !== undefined ? fresh[k] : edited[k])
  })
  return o
}

/* ============ 加载 ============ */
async function loadGovScript() {
  const file = GOV_FILES[govMode.value]
  // 模块调速器脚本目录为大写 A/B/C（activeScheme 是小写方案号，直接拼路径会指向不存在的目录）
  const govDir = activeScheme === 'b' ? 'B' : activeScheme === 'c' ? 'C' : 'A'
  const t = await readText(MOD + '/' + govDir + '/' + file)
  if (!t) showToast('读取 ' + govDir + '/' + file + ' 失败，当前显示默认值')
  const d = parseGovScript(t)
  if (!d.governor) d.governor = schemeGovName()
  if (!sliderDefsFor(d)) {
    ;['upThreshold', 'downThreshold', 'freqStep', 'samplingRate', 'targetLoads'].forEach(k => {
      if (!d[k]) d[k] = '0'
    })
  }
  govData.value = d
  govInitial = JSON.parse(JSON.stringify(d))
}

async function switchMode(k) {
  govMode.value = k
  try {
    await loadGovScript()
    await refreshGovNow()
  } catch (e) {
    showToast('加载失败')
  }
}

async function init() {
  if (busy) return
  busy = true
  loading.value = true
  unavail.value = null
  ddOpen.value = false
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
    if (MODE_KEYS.includes(curMode)) govMode.value = curMode

    await detectSoc()
    // 方案选择统一走 script/fangan.sh（与 main.sh / install.sh 同一实现）
    await detectActiveConf()
    // 按本机可用调速器过滤下拉列表（读 policy0 scaling_available_governors）：
    // 不可用的调速器不再显示，本机额外的自定义调速器补充进去
    const av = await shOut('cat /sys/devices/system/cpu/cpufreq/policy0/scaling_available_governors 2>/dev/null')
    if (av) {
      const list = av.split(/\s+/).filter(Boolean)
      govAvail.value = PRESETS.filter(p => list.includes(p)).concat(list.filter(g => !PRESETS.includes(g)))
    } else {
      govAvail.value = PRESETS.slice()
    }
    await loadGovScript()
    const sn = activeScheme === 'a' ? '1' : activeScheme === 'b' ? '2' : '3'
    socTip.value = socInfo.known
      ? '当前SOC：' + socInfo.code + '（' + socInfo.marketing + '）→ 加载方案 ' + sn
      : '当前SOC：' + socInfo.code + ' → 按调速器检测加载方案 ' + sn
    await refreshGovNow()
  } catch (e) {
    unavail.value = { t: '初始化失败', s: '请稍后重试' }
  } finally {
    busy = false
    loading.value = false
  }
}

/* ============ 保存（三方合并 + 应用） ============ */
async function saveGov() {
  if (saving) return
  saving = true
  try {
    await detectActiveConf()       // 与 main.sh 一致（统一走 fangan.sh）
    const file = GOV_FILES[govMode.value]
    // 调速器脚本目录为大写 A/B/C（小写方案号拼路径会写到不存在的目录导致保存失败）
    const govDir = activeScheme === 'b' ? 'B' : activeScheme === 'c' ? 'C' : 'A'
    const t = await readText(MOD + '/' + govDir + '/' + file)
    if (!t) { showToast('调速器脚本读取失败，已阻止保存（防止清空脚本）'); return }
    // 重读磁盘最新内容后三方合并：页面旧快照不会覆盖 APP/另一端的改动
    const merged = mergeGovData(parseGovScript(t), govData.value, govInitial || {})
    const mergedScript = buildGovScript(t, merged)
    const r = await writeFile(mergedScript, MOD + '/' + govDir + '/' + file)
    if (!r || !r.ok) { showToast('写入失败'); return }
    govData.value = merged
    govInitial = JSON.parse(JSON.stringify(merged))
    if (govMode.value === curMode) {
      // 统一调度接口（kzlx=1 内部语义：仅应用）
      await exec('sh /data/powercfg.sh ' + govMode.value + ' 1', 20)
      showToast('已保存并实时应用')
    } else {
      showToast('已保存（切换到该模式时生效）')
    }
    await refreshGovNow()
  } catch (e) {
    showToast('保存失败')
  } finally {
    saving = false
  }
}

onMounted(() => {
  init()
  document.addEventListener('click', onDocClick)
})
/* keep-alive：页面重新可见时重读磁盘与实时调速器（对齐蓝本 go('gov') 行为） */
onActivated(() => { init() })
onDeactivated(() => { ddOpen.value = false })
onBeforeUnmount(() => { document.removeEventListener('click', onDocClick) })
</script>
