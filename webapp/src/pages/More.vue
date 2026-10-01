<template>
  <div>
    <!-- ===== 主题设置 ===== -->
    <div class="card">
      <div class="row-between">
        <b style="font-size:15px">主题设置</b>
      </div>

      <div style="font-size:13px;font-weight:800;margin:10px 0 2px">外观</div>
      <div class="chips" style="margin:6px 0 0">
        <button class="chip" :class="{ on: !theme.dark }" style="font-family:inherit" @click="setDark(false)"><span class="dot" style="background:#F5F7FC"></span>日间</button>
        <button class="chip" :class="{ on: theme.dark }" style="font-family:inherit" @click="setDark(true)"><span class="dot" style="background:#0D1220"></span>夜间</button>
      </div>

      <div style="font-size:13px;font-weight:800;margin:12px 0 2px">背景沉浸</div>

      <div class="row-between" style="padding:9px 0">
        <span style="font-size:13px;font-weight:700">全透明背景</span>
        <button class="switch" :class="{ on: theme.transparent }" @click="toggleTransparent"></button>
      </div>

      <div class="sl">
        <label>控件透明度</label>
        <input type="range" min="30" max="100" step="1" :value="theme.glass" :style="pctStyle(theme.glass, 30, 100)" @input="onSlide('glass', $event)">
        <output>{{ theme.glass }}%</output>
      </div>

      <div class="row-between" style="padding:9px 0">
        <span style="font-size:13px;font-weight:700">液态玻璃</span>
        <button class="switch" :class="{ on: theme.liquid }" @click="toggleLiquid"></button>
      </div>

      <div style="height:1px;background:var(--stroke);margin:10px 0 2px"></div>

      <div class="row-between" style="padding:9px 0">
        <span style="font-size:13px;font-weight:700">自定义背景图片</span>
        <button class="switch" :class="{ on: theme.imageBg }" @click="toggleImage"></button>
      </div>
      <template v-if="theme.imageBg">
        <div v-if="theme.bgUrl" :style="bgPreview" style="height:110px;border-radius:12px;margin:4px 0 2px"></div>
        <button class="mini-btn" style="font-family:inherit;margin-top:8px" @click="pickBg">选择图片</button>
        <div class="hint" style="margin-top:6px">{{ theme.hasImage ? '背景图更换后即时生效' : '尚未选择背景图，点击“选择图片”选取' }}</div>
      </template>

      <div style="height:1px;background:var(--stroke);margin:10px 0 2px"></div>

      <div class="sl">
        <label>背景透明度</label>
        <input type="range" min="5" max="100" step="1" :value="theme.bgAlpha" :style="pctStyle(theme.bgAlpha, 5, 100)" @input="onSlide('bgAlpha', $event)">
        <output>{{ theme.bgAlpha }}%</output>
      </div>
      <div class="sl">
        <label>背景缩放</label>
        <input type="range" min="100" max="300" step="1" :value="theme.bgScale" :style="pctStyle(theme.bgScale, 100, 300)" @input="onSlide('bgScale', $event)">
        <output>{{ theme.bgScale }}%</output>
      </div>
      <div class="sl">
        <label>取景偏移 左右</label>
        <input type="range" min="-50" max="50" step="1" :value="theme.bgOffX" :style="pctStyle(theme.bgOffX, -50, 50)" @input="onSlide('bgOffX', $event)">
        <output>{{ theme.bgOffX }}%</output>
      </div>
      <div class="sl">
        <label>取景偏移 上下</label>
        <input type="range" min="-50" max="50" step="1" :value="theme.bgOffY" :style="pctStyle(theme.bgOffY, -50, 50)" @input="onSlide('bgOffY', $event)">
        <output>{{ theme.bgOffY }}%</output>
      </div>
    </div>

    <!-- ===== 功耗记录 ===== -->
    <div class="card">
      <div class="chart-header">
        <span class="chart-title">功耗记录曲线</span>
        <div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap">
          <div class="chart-legend">
            <span class="legend-pill"><span style="width:7px;height:7px;border-radius:50%;background:#67d98a;display:inline-block"></span>放电</span>
            <span class="legend-pill"><span style="width:7px;height:7px;border-radius:50%;background:#ffb02e;display:inline-block"></span>充电</span>
            <span class="legend-pill"><span style="width:7px;height:7px;border-radius:50%;background:#ff8086;display:inline-block"></span>温度</span>
          </div>
          <button class="mini-btn ghost" style="font-family:inherit" @click="doClear">清除日志</button>
        </div>
      </div>

      <div class="chips" style="margin-bottom:8px">
        <button v-for="r in RANGES" :key="r.h" class="chip" :class="{ on: rangeH === r.h }" style="font-family:inherit" @click="rangeH = r.h">{{ r.name }}</button>
      </div>

      <div class="unavail" v-if="loadErr"><b>功耗记录读取失败</b><br>{{ loadErr }}</div>
      <div class="unavail" v-else-if="!samples.length && loading">读取中…</div>
      <div class="unavail" v-else-if="!samples.length">暂无记录 · 保持应用运行将自动采样（每分钟一条）</div>
      <template v-else>
        <div class="unavail" v-if="!windowRows.length" style="padding:16px 6px">该时间范围内暂无采样记录</div>
        <div class="chart-frame" v-if="windowRows.length"><canvas ref="cv"></canvas></div>

        <div class="grid" style="margin-top:10px" v-if="lastSession">
          <div class="cell"><i>最近会话</i><b>{{ lastSession.charging ? '充电' : '放电' }} {{ lvlText(lastSession) }}</b></div>
          <div class="cell"><i>会话时长</i><b>{{ fmtDur(lastSession.durMs) }}</b></div>
          <div class="cell"><i>平均功率</i><b>{{ lastSession.avgW.toFixed(2) }}<em>W</em></b></div>
          <div class="cell"><i>峰值功率</i><b>{{ lastSession.maxW.toFixed(2) }}<em>W</em></b></div>
          <div class="cell"><i>会话能耗</i><b>{{ lastSession.energyWh.toFixed(1) }}<em>Wh</em></b></div>
          <div class="cell"><i>记录条数</i><b>{{ windowRows.length }}</b></div>
        </div>

        <template v-if="sessions.length > 1">
          <div class="group" style="margin-top:12px">历史会话</div>
          <div class="hint" style="margin-top:2px">
            <div v-for="(h, i) in histLines" :key="i" style="margin-bottom:3px">{{ h }}</div>
          </div>
        </template>
      </template>
      <div class="hint">应用运行时每分钟自动采样一条，最多保留约 10 天</div>
    </div>

    <!-- ===== 关于 ===== -->
    <div class="card">
      <div class="row-between">
        <b style="font-size:15px">关于</b>
        <span style="font-size:12px;color:var(--t2)">版本 1.4.1 · Vue 架构</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onActivated } from 'vue'
import { theme, setTheme, refreshBgImage } from '../theme'
import { showToast, confirmBox } from '../ui'
import { powerCsv, clearPowerCsv, pickImage } from '../bridge'
import Chart from 'chart.js/auto'

const RANGES = [
  { h: 1, name: '近1小时' },
  { h: 24, name: '近24小时' },
  { h: 72, name: '近3天' },
  { h: 168, name: '近7天' }
]
/** 会话断流阈值：超过 15 分钟无采样则视为新会话（PowerHistoryManager） */
const GAP_MS = 15 * 60000
/** 曲线降采样上限（对齐 WebUI） */
const MAXPTS = 600

const rangeH = ref(24)
/** 全部采样（时间正序）：{t, w, temp, level, charging} */
const samples = ref([])
/** 会话分段（最新在前） */
const sessions = ref([])
const loading = ref(false)
const loadErr = ref('')
const cv = ref(null)
let chart = null
let chartTries = 0
let skipFirstActivate = true
/** 充电标记数组（曲线分段配色 / tooltip 共用，每次渲染刷新） */
let chgFlags = []

const windowRows = computed(() => {
  const cutoff = Date.now() - rangeH.value * 3600000
  return samples.value.filter(s => s.t >= cutoff)
})
const lastSession = computed(() => sessions.value[0] || null)

const histLines = computed(() => sessions.value.slice(0, 4).map(s => {
  const durSec = Math.max(1, Math.floor((s.end - s.start) / 1000))
  return fmtFull(s.start) + ' ~ ' + fmtFull(s.end)
    + '（' + Math.floor(durSec / 60) + '分' + p2(durSec % 60) + '秒）'
    + (s.charging ? ' 充电 ' : ' 放电 ')
    + lvl(s.startLevel) + '%→' + lvl(s.endLevel) + '%'
    + ' · 平均 ' + s.avgW.toFixed(1) + 'W · 峰值 ' + s.maxW.toFixed(1) + 'W · ' + s.energyWh.toFixed(1) + 'Wh'
}))

const bgPreview = computed(() => theme.bgUrl
  ? { backgroundImage: 'url("' + theme.bgUrl + '")', backgroundSize: 'cover', backgroundPosition: 'center' }
  : null)

// ==================== 主题设置 ====================

async function setDark(v) {
  if (theme.dark !== v) await setTheme('dark', v)
}

async function toggleTransparent() {
  const on = !theme.transparent
  if (on && theme.imageBg) await setTheme('imageBg', false)   // 与自定义背景互斥
  await setTheme('transparent', on)
}

async function toggleImage() {
  const on = !theme.imageBg
  if (on && theme.transparent) await setTheme('transparent', false)   // 与全透明背景互斥
  await setTheme('imageBg', on)
  if (on) pickBg()
}

async function toggleLiquid() {
  await setTheme('liquid', !theme.liquid)
}

async function onSlide(key, e) {
  await setTheme(key, Number(e.target.value))
}

function pickBg() {
  pickImage().catch(() => showToast('未找到可用的图片选择器'))
}

// ==================== 功耗记录 ====================

async function loadPower() {
  if (loading.value) return
  loading.value = true
  loadErr.value = ''
  try {
    samples.value = parseCsv(await powerCsv())
    sessions.value = buildSessions(samples.value, 6)
  } catch (e) {
    loadErr.value = e?.message || '读取失败'
  }
  loading.value = false
  renderChart()
}

/**
 * 解析采样 CSV（契约列序：时间,功率,电压,温度,电量,充放状态）。
 * 兼容 APP 旧列序（时间,电量,功率,状态,温度）；时间秒/毫秒自适应。
 */
function parseCsv(text) {
  const out = []
  if (!text) return out
  for (let rawLine of String(text).split('\n')) {
    const line = rawLine.trim()
    if (!line || line.startsWith('#')) continue
    const p = line.split(',')
    if (p.length < 4) continue
    const t0 = parseFloat(p[0])
    if (!isFinite(t0)) continue
    const t = t0 > 1e12 ? t0 : t0 * 1000
    if (p.length >= 6) {
      out.push(mk(t, parseFloat(p[1]), parseFloat(p[3]), parseFloat(p[4]), normSt(p[5])))
    } else if (p.length === 5 && /^[CDF]$/i.test(p[3].trim())) {
      out.push(mk(t, parseFloat(p[2]), parseFloat(p[4]), parseFloat(p[1]), p[3].trim().toUpperCase()))
    }
  }
  out.sort((a, b) => a.t - b.t)
  return out
}

function mk(t, w, tempC, level, st) {
  return {
    t,
    w: isFinite(w) ? w : 0,
    temp: isFinite(tempC) ? tempC : -1,
    level: isFinite(level) ? Math.round(level) : -1,
    charging: st === 'C'
  }
}

/** 充放状态归一化：1/C/Charging=充电，F/Full=满电（归入非充电），其余=放电 */
function normSt(s) {
  s = String(s ?? '').trim()
  if (s === '1' || /^C/i.test(s)) return 'C'
  if (/^F/i.test(s)) return 'F'
  return 'D'
}

/** 由采样分段生成会话（充电/放电，F 归入非充电），最新在前（PowerHistoryManager 语义） */
function buildSessions(list, limit) {
  const out = []
  for (let i = list.length - 1; i >= 0 && out.length < limit; i--) {
    const cur = list[i]
    const charging = cur.charging
    const top = out[out.length - 1]
    if (top && top.charging === charging && top.start - cur.t <= GAP_MS) {
      top.start = cur.t
      top.startLevel = cur.level
      top.sumW += cur.w
      top.maxW = Math.max(top.maxW, Math.abs(cur.w))
      top.samples++
      continue
    }
    out.push({
      charging, start: cur.t, end: cur.t, startLevel: cur.level, endLevel: cur.level,
      sumW: cur.w, maxW: Math.abs(cur.w), samples: 1
    })
  }
  for (const s of out) {
    s.durMs = Math.max(1000, s.end - s.start)
    const durMin = Math.max(1, (s.end - s.start) / 60000)
    s.energyWh = s.sumW * durMin / 60
    s.avgW = s.samples > 0 ? s.sumW / s.samples : 0
  }
  return out
}

async function doClear() {
  const ok = await confirmBox('清空功耗记录', '确定清空全部功耗采样记录？清空后不可恢复。', '清空', '取消')
  if (!ok) return
  try {
    await clearPowerCsv()
    showToast('已清空功耗记录')
    samples.value = []
    sessions.value = []
    renderChart()
  } catch (e) {
    showToast('清空失败：' + (e?.message || e))
  }
}

function cssVar(k, f) {
  const v = getComputedStyle(document.documentElement).getPropertyValue(k).trim()
  return v || f
}

function renderChart() {
  const rows = windowRows.value
  if (!rows.length) {
    if (chart) { chart.destroy(); chart = null }
    return
  }
  const c = cv.value
  if (!c || !c.clientWidth) {
    if (chartTries++ < 60) requestAnimationFrame(renderChart)   // 等布局完成，避免图表空白/错乱
    return
  }
  chartTries = 0

  // 降采样到最多 600 桶（桶内取均值）
  const labels = [], power = [], temp = [], chgs = []
  if (rows.length > MAXPTS) {
    const bucket = rows.length / MAXPTS
    for (let i = 0; i < MAXPTS; i++) {
      const seg = rows.slice(Math.floor(i * bucket), Math.max(Math.floor((i + 1) * bucket), Math.floor(i * bucket) + 1))
      const w = seg.reduce((a, x) => a + Math.abs(x.w), 0) / seg.length
      const tps = seg.filter(x => x.temp >= 0)
      labels.push(fmtClock(seg[0].t))
      power.push(Math.round(w * 100) / 100)
      temp.push(tps.length ? Math.round(tps.reduce((a, x) => a + x.temp, 0) / tps.length * 10) / 10 : null)
      chgs.push(seg.filter(x => x.charging).length >= seg.length / 2)
    }
  } else {
    for (const r of rows) {
      labels.push(fmtClock(r.t))
      power.push(Math.round(Math.abs(r.w) * 100) / 100)
      temp.push(r.temp >= 0 ? Math.round(r.temp * 10) / 10 : null)
      chgs.push(!!r.charging)
    }
  }
  chgFlags = chgs

  if (!chart) {
    chart = new Chart(c, {
      type: 'line',
      data: {
        labels,
        datasets: [
          {
            label: '功率', data: power, yAxisID: 'yPower', borderWidth: 1.5, fill: false, tension: 0.25,
            pointRadius: 0, pointHoverRadius: 4, pointHitRadius: 18, borderCapStyle: 'round', borderJoinStyle: 'round',
            borderColor: cssVar('--chart-power-line', '#67d98a'),
            segment: { borderColor: ctx => chgFlags[ctx.p1DataIndex] ? '#ffb02e' : cssVar('--chart-power-line', '#67d98a') }
          },
          {
            label: '温度', data: temp, yAxisID: 'yTemp', borderWidth: 1.5, fill: false, tension: 0.25,
            borderColor: cssVar('--chart-temp-line', '#ff8086'),
            pointRadius: 0, pointHoverRadius: 4, pointHitRadius: 18, borderCapStyle: 'round', borderJoinStyle: 'round'
          }
        ]
      },
      options: {
        responsive: true, maintainAspectRatio: false,
        devicePixelRatio: Math.min(Math.max(window.devicePixelRatio || 1, 1), 4),
        animation: false, spanGaps: true,
        interaction: { mode: 'index', intersect: false, axis: 'x' },
        layout: { padding: { top: 16, right: 14, bottom: 2, left: 2 } },
        scales: {
          x: {
            grid: { color: cssVar('--chart-grid', 'rgba(45,140,255,.055)'), drawTicks: false },
            ticks: { color: cssVar('--chart-text', '#7f8896'), autoSkip: true, maxTicksLimit: 6, font: { size: 10 } }
          },
          yPower: {
            position: 'left', min: 0, suggestedMax: 12,
            grid: { color: cssVar('--chart-grid', 'rgba(45,140,255,.055)'), drawTicks: false },
            ticks: { color: cssVar('--chart-text', '#7f8896'), callback: v => v + 'W', font: { size: 10 }, maxTicksLimit: 6 }
          },
          yTemp: {
            position: 'right', min: 15, suggestedMax: 45,
            grid: { drawOnChartArea: false, drawTicks: false },
            ticks: { color: cssVar('--chart-text', '#7f8896'), callback: v => v + '℃', font: { size: 10 }, maxTicksLimit: 6 }
          }
        },
        plugins: {
          legend: { display: false },
          tooltip: {
            backgroundColor: 'rgba(20,31,56,.94)', titleColor: '#fff', bodyColor: '#fff',
            displayColors: true, padding: 10, cornerRadius: 12,
            position: 'nearest', caretSize: 6, caretPadding: 10,
            titleFont: { size: 11, weight: '700' }, bodyFont: { size: 10, weight: '600' },
            callbacks: {
              label: ctx => ctx.dataset.label === '功率'
                ? ' 功率 ' + ctx.parsed.y + ' W（' + (chgFlags[ctx.dataIndex] ? '充电' : '放电') + '）'
                : ' 温度 ' + ctx.parsed.y + ' ℃'
            }
          }
        }
      }
    })
  } else {
    chart.data.labels = labels
    chart.data.datasets[0].data = power
    chart.data.datasets[1].data = temp
  }

  // 配色实时取自 CSS 变量（日/夜切换后跟随）
  chart.data.datasets[0].borderColor = cssVar('--chart-power-line', '#67d98a')
  chart.data.datasets[0].segment = { borderColor: ctx => chgFlags[ctx.p1DataIndex] ? '#ffb02e' : cssVar('--chart-power-line', '#67d98a') }
  chart.data.datasets[1].borderColor = cssVar('--chart-temp-line', '#ff8086')
  const grid = cssVar('--chart-grid', 'rgba(45,140,255,.055)')
  const txt = cssVar('--chart-text', '#7f8896')
  chart.options.scales.x.grid.color = grid
  chart.options.scales.x.ticks.color = txt
  chart.options.scales.yPower.grid.color = grid
  chart.options.scales.yPower.ticks.color = txt
  chart.options.scales.yTemp.ticks.color = txt

  // 量程自适应（对齐 WebUI）
  const pv = power.filter(v => v != null)
  if (pv.length) chart.options.scales.yPower.max = Math.max(10, Math.ceil(1.15 * Math.max(...pv)))
  const tv = temp.filter(v => v != null)
  if (tv.length) {
    chart.options.scales.yTemp.min = Math.max(15, Math.floor(Math.min(...tv) - 2))
    chart.options.scales.yTemp.max = Math.max(40, Math.ceil(Math.max(...tv) + 2))
  }
  chart.update('none')
}

// 日/夜切换后曲线配色跟随
watch(() => theme.dark, () => renderChart())
// 时间范围切换后重绘
watch(rangeH, () => renderChart())

// ==================== 工具 ====================

function pctStyle(v, min, max) {
  return { '--p': ((v - min) / (max - min) * 100) + '%' }
}

function lvl(v) { return v >= 0 ? v : '--' }
function lvlText(s) { return lvl(s.startLevel) + '%→' + lvl(s.endLevel) + '%' }

const p2 = n => String(n).padStart(2, '0')

function fmtClock(t) {
  return new Date(t).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
}

/** MM-dd HH:mm:ss（历史会话时间，对齐原生 historyText） */
function fmtFull(t) {
  const d = new Date(t)
  return p2(d.getMonth() + 1) + '-' + p2(d.getDate()) + ' ' + p2(d.getHours()) + ':' + p2(d.getMinutes()) + ':' + p2(d.getSeconds())
}

/** 时长格式化（精确到秒）：1h23m45s / 41m32s / 45s（对齐原生 fmtDur） */
function fmtDur(ms) {
  let s = Math.max(0, Math.floor(ms / 1000))
  const h = Math.floor(s / 3600)
  s %= 3600
  const m = Math.floor(s / 60)
  s %= 60
  if (h > 0) return h + 'h' + m + 'm' + s + 's'
  if (m > 0) return m + 'm' + s + 's'
  return s + 's'
}

onMounted(() => {
  loadPower()
  // 已有背景图但尚未拉取 dataURL（bgChanged 事件只在换图时触发）
  if (theme.imageBg && theme.hasImage && !theme.bgUrl) refreshBgImage()
})

onActivated(() => {
  if (skipFirstActivate) { skipFirstActivate = false; return }
  loadPower()   // 重新可见时刷新采样
  if (theme.imageBg && theme.hasImage && !theme.bgUrl) refreshBgImage()
})
</script>
