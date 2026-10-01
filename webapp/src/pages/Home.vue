<template>
  <div>
    <!-- SOC 识别行 -->
    <div v-if="socInfo" class="soc-row">当前SOC：{{ socInfo.marketing }}</div>

    <!-- 实时功耗卡片 -->
    <div class="card liquid-glass">
      <div class="chart-header">
        <div class="chart-title">实时功耗</div>
        <div style="display:flex;align-items:center;gap:8px">
          <span id="pwBadge" class="chart-title" :style="{color: charging ? 'var(--ok)' : 'var(--ac)'}"
                style="font-size:13px">{{ powerStatus }}</span>
          <span class="root-badge" :class="rootCls" @click="onRootBadge">{{ rootText }}</span>
        </div>
      </div>
      <div class="pw-toprow">
        <div class="pw-stat"><small>功耗 <em style="font-style:normal;color:var(--t3);cursor:pointer"
              @click="pickCellMode">{{ cellText }}</em></small>
          <b id="pwBadge2" :style="{color: watts > 0 ? 'var(--ac)' : 'var(--t3)'}">{{ watts > 0 ? watts.toFixed(2) + ' W' : '--' }}</b>
          <small style="color:var(--t3);font-size:10px">峰值 {{ peakW > 0 ? peakW.toFixed(2) + ' W' : '--' }}</small>
        </div>
        <div class="pw-stat"><small>电流</small><b>{{ amps !== 0 ? Math.abs(amps * 1000).toFixed(0) + ' mA' : '--' }}</b></div>
        <div class="pw-stat"><small>电压 / 电量</small><b>{{ volts > 0 ? volts.toFixed(2) + ' V' : '--' }}</b>
          <small style="color:var(--t3);font-size:10px">{{ level >= 0 ? level + '%' : '--' }} · {{ tempC > 0 ? tempC.toFixed(1) + '℃' : '--' }}</small></div>
      </div>
      <div class="chart-frame" style="height:150px">
        <canvas ref="rtCanvas"></canvas>
      </div>
      <div class="hint">电芯判定为并联双电芯时电压与单芯无异，点击"功耗"标签可手动指定电芯模式。</div>
    </div>

    <!-- 模式快切 -->
    <div class="card liquid-glass">
      <div class="row-between" style="margin-bottom:10px">
        <b style="font-size:15px">模式切换</b>
        <span style="font-size:13px;color:var(--t3)">当前：
          <b :style="{color: curMode ? modeColors[curModeIdx] : 'var(--t3)'}">{{ curMode ? modeNames[curModeIdx] : '--' }}</b>
        </span>
      </div>
      <div class="chips">
        <div v-for="(m, i) in modeKeys" :key="m" class="chip" :class="{on: curMode === m}"
             :style="curMode === m ? {borderColor: modeColors[i], color: modeColors[i], background: modeColors[i] + '1A'} : {}"
             @click="applyMode(m)">
          <span class="dot" :style="{background: modeColors[i]}"></span>{{ modeNames[i] }}
        </div>
      </div>
      <div class="desc">切换后立即应用并同步为默认模式（动态切换持续运行）。</div>
    </div>

    <!-- 功耗统计（近3小时） -->
    <div class="card liquid-glass">
      <div class="row-between" @click="histExpanded = !histExpanded; if(histExpanded) refreshHist()">
        <div class="entry" style="cursor:pointer">
          <div class="bar" style="background:var(--mc1)"></div>
          <div class="tx"><b>功耗统计</b><i>{{ histHint }}</i></div>
        </div>
        <span style="color:var(--t3);font-size:12px">{{ histExpanded ? '▴' : '▾' }}</span>
      </div>
      <div v-show="histExpanded" @click="goRecords" style="cursor:pointer;margin-top:8px">
        <canvas ref="sparkCanvas" style="width:100%;height:52px;display:block"></canvas>
      </div>
    </div>

    <!-- CPU 核心 -->
    <div class="card liquid-glass">
      <div class="row-between" @click="cpuExpanded = !cpuExpanded; if(cpuExpanded) refreshCpu(true)">
        <div class="entry" style="cursor:pointer">
          <div class="bar" style="background:var(--mc2)"></div>
          <div class="tx"><b>CPU 核心</b><i>{{ cpuSummary }}</i></div>
        </div>
        <span style="color:var(--t3);font-size:12px">{{ cpuExpanded ? '管理 ▴' : '管理 ▾' }}</span>
      </div>
      <div v-show="!cpuExpanded" style="display:flex;gap:6px;flex-wrap:wrap;margin-top:10px">
        <div v-for="(c, i) in cores" :key="i"
             :style="{width:'14px',height:'14px',borderRadius:'4px',
               background: c.online ? 'rgba(0,150,200,' + dotAlpha(c) + ')' : 'transparent',
               border: c.online ? 'none' : '2px solid var(--stroke)'}"></div>
      </div>
      <div v-show="cpuExpanded">
        <div v-for="(c, i) in cores" :key="'r' + i" class="row-between" style="padding:5px 0">
          <span style="font-size:12px" :style="{color: c.online ? 'var(--t1)' : 'var(--t3)'}">
            CPU{{ i }}{{ clusterLabel(i) ? ' · ' + clusterLabel(i) : '' }}{{ c.online ? (c.freqMhz > 0 ? ' · ' + c.freqMhz + ' MHz' : '') : ' · 已停用' }}</span>
          <button v-if="i === 0" class="mini-btn ghost" style="opacity:.6;cursor:default;padding:3px 10px">主核</button>
          <button v-else class="mini-btn" :class="{ghost: !c.online}" style="padding:3px 12px"
                  @click="toggleCore(i)">{{ c.online ? '✓' : '' }}</button>
        </div>
        <div class="hint">CPU0 为主核不可关闭；开关直接写内核 sysfs，立即生效。</div>
      </div>
    </div>

    <!-- 悬浮窗管理 -->
    <div class="card liquid-glass">
      <div class="entry">
        <div class="bar" style="background:var(--ac2)"></div>
        <div class="tx"><b>悬浮窗管理</b><i>负载 · 进程 · 迷你 · 温度</i></div>
      </div>
      <div style="margin-top:8px">
        <div v-for="(o, i) in overlays" :key="o.key" class="row-between" style="padding:6px 0">
          <span style="font-size:13px">{{ o.name }}</span>
          <button class="switch" :class="{on: o.on}" @click="toggleOverlay(i)"></button>
        </div>
      </div>
      <div class="hint">开启需要悬浮窗权限，未授权时会自动跳转授权页。</div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, onActivated, onDeactivated } from 'vue'
import Chart from 'chart.js/auto'
import * as bridge from '../bridge'
import { showToast, pickOptions } from '../ui'

// ===== 模式 =====
const modeKeys = ['powersave', 'balance', 'performance', 'fast']
const modeNames = ['省电', '均衡', '性能', '极速']
const modeColors = ['#00B5A3', '#0096C8', '#E08A00', '#A02CF0']
const MOD = '/data/adb/modules/colorFC'
const CUR_MODE_FILE = '/sdcard/Android/qingtd/cur_powermode.txt'

const curMode = ref(null)
const curModeIdx = computed(() => modeKeys.indexOf(curMode.value))
const applying = ref(false)

// ===== 功耗 =====
const watts = ref(0), amps = ref(0), volts = ref(0), level = ref(-1), tempC = ref(0)
const peakW = ref(0)
const status = ref('')
const charging = computed(() => status.value === 'Charging' || status.value === 'Full' || status.value === 'Not charging')
const powerStatus = computed(() => {
  if (!status.value) return amps.value > 0 ? '放电中' : '--'
  if (status.value === 'Charging') return '充电中'
  if (status.value === 'Full') return '已充满'
  if (status.value === 'Not charging') return '未充电'
  return '放电中'
})
let cellMode = 0, lastAutoCells = 1
const cellText = computed(() => {
  const cells = cellMode === 0 ? lastAutoCells : cellMode
  return (cells >= 2 ? '双电芯' : '单电芯') + (cellMode === 0 ? '·已校准' : '·手动')
})

// ===== Root =====
const rooted = ref(false), rootChecked = ref(false), rootPending = ref(false)
const rootText = computed(() => rootPending.value ? 'ROOT 待授权' : (!rootChecked.value ? 'ROOT …' : (rooted.value ? 'ROOT 已授权' : '无 ROOT')))
const rootCls = computed(() => rootPending.value ? 'pending' : (rooted.value ? 'ok' : 'no'))

// ===== SOC =====
const socInfo = ref(null)

// ===== 功耗统计 =====
const histExpanded = ref(false)
const histHint = ref('点击展开近3小时曲线')
const sparkCanvas = ref(null)

// ===== CPU =====
const cores = ref([])
const cpuExpanded = ref(false)
const topo = ref([])
const cpuSummary = computed(() => {
  if (!cores.value.length) return '读取中…'
  const on = cores.value.filter(c => c.online).length
  return cores.value.length + '核 · ' + on + '在线'
})
let cpuSwitching = false

// ===== 悬浮窗 =====
const overlays = ref([
  { key: 'load', name: '负载悬浮窗', on: false },
  { key: 'proc', name: '进程悬浮窗', on: false },
  { key: 'mini', name: '迷你悬浮窗', on: false },
  { key: 'temp', name: '温度悬浮窗', on: false }
])

// ===== 实时曲线（30 点滚动） =====
const rtCanvas = ref(null)
let rtChart = null
const rtData = { w: [], t: [], chg: [] }
const MAX_PTS = 30

let timer = 0, alive = false, busy = false

function cssVar(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || '#67d98a'
}

function initRtChart() {
  if (!rtCanvas.value || rtChart) return
  rtChart = new Chart(rtCanvas.value, {
    type: 'line',
    data: {
      labels: [],
      datasets: [
        {
          label: '功耗', data: [], yAxisID: 'y',
          borderColor: cssVar('--chart-power-line'),
          backgroundColor: 'transparent', borderWidth: 2, tension: .35,
          pointRadius: 2, pointBackgroundColor: ctx => {
            const i = ctx.dataIndex
            return (i >= 0 && rtData.chg[i]) ? '#ffb02e' : cssVar('--chart-power-line')
          }
        },
        {
          label: '温度', data: [], yAxisID: 'y1',
          borderColor: cssVar('--chart-temp-line'),
          backgroundColor: 'transparent', borderWidth: 1.5, tension: .35,
          pointRadius: 0, borderDash: [4, 3]
        }
      ]
    },
    options: {
      responsive: true, maintainAspectRatio: false, animation: false,
      interaction: { mode: 'index', intersect: false },
      plugins: {
        legend: { display: false },
        tooltip: { enabled: false }
      },
      scales: {
        x: { display: false },
        y: { position: 'left', grid: { color: cssVar('--chart-grid') }, ticks: { color: cssVar('--chart-text'), font: { size: 9 }, maxTicksLimit: 5, callback: v => v + 'W' } },
        y1: { position: 'right', grid: { display: false }, ticks: { color: cssVar('--chart-text'), font: { size: 9 }, maxTicksLimit: 4, callback: v => v + '°' } }
      }
    }
  })
}

function pushRt(w, t, chg) {
  rtData.w.push(w); rtData.t.push(t); rtData.chg.push(chg)
  if (rtData.w.length > MAX_PTS) { rtData.w.shift(); rtData.t.shift(); rtData.chg.shift() }
  if (rtChart) {
    rtChart.data.labels = rtData.w.map(() => '')
    rtChart.data.datasets[0].data = rtData.w.slice()
    rtChart.data.datasets[1].data = rtData.t.slice()
    rtChart.update('none')
  }
}

// ===== 主循环（1 秒） =====
async function tick() {
  if (busy) return
  busy = true
  try {
    const st = await bridge.readBattery()
    if (st) {
      lastAutoCells = st.cells || 1
      const w = await bridge.applyCellMode(st, cellMode)
      watts.value = Math.abs(w)
      amps.value = st.amps || 0
      volts.value = st.volts || 0
      level.value = st.level
      tempC.value = st.tempC || 0
      status.value = st.status || ''
      if (watts.value > peakW.value) peakW.value = watts.value
      if (watts.value > 0) pushRt(watts.value, tempC.value, charging.value)
    }
    const sp = await bridge.cpuSnapshot()
    if (sp && sp.cores) cores.value = Array.from({ length: sp.cores }, (_, i) => ({
      online: sp.online ? !!sp.online[i] : true,
      freqMhz: sp.freqMhz ? (sp.freqMhz[i] || -1) : -1,
      busy: sp.busy ? (sp.busy[i] || 0) : 0
    }))
  } catch (e) { /* 桥未就绪时静默 */ }
  busy = false
}

function startLoop() {
  if (alive) return
  alive = true
  tick()
  timer = setInterval(tick, 1000)
}

function stopLoop() {
  alive = false
  if (timer) { clearInterval(timer); timer = 0 }
}

function dotAlpha(c) {
  return Math.round((89 + 166 * Math.min(1, Math.max(0, (c.busy || 0) / 100))) )
}

// ===== 模式切换（与原生/WebUI 同一入口 powercfg.sh） =====
async function applyMode(mode) {
  if (applying.value) return
  const idx = modeKeys.indexOf(mode)
  if (idx < 0) return
  if (rootChecked.value && !rooted.value) {
    showToast('需要 ROOT 权限，请先在管理器授权')
    return
  }
  applying.value = true
  showToast('正在应用 ' + modeNames[idx] + ' …')
  try {
    let peiz = await bridge.readFile(MOD + '/files/peiz')
    if (!peiz || !peiz.trim()) peiz = 'all'
    const cmd = 'if [ -f /data/powercfg.sh ]; then sh /data/powercfg.sh ' + mode + ' manual; ' +
      'else cd /sdcard/Android/qingtd 2>/dev/null && for f in *.conf; do [ -f "$f" ] || continue; ' +
      'grep -q \'^moren=\' "$f" && sed -i \'s/^moren=.*/moren=' + mode + '/\' "$f" || echo "moren=' + mode + '" >> "$f"; done; ' +
      'sh ' + MOD + '/script/main.sh ' + mode + ' ' + MOD + '/files ' + peiz.trim() + '; fi; true'
    const r = await bridge.exec(cmd, 25)
    if (r && r.ok) {
      curMode.value = mode
      showToast(modeNames[idx] + ' 已应用（并设为默认模式）')
    } else {
      showToast('应用失败：' + ((r && r.err && r.err.trim()) || 'ROOT 执行失败'))
    }
  } catch (e) {
    showToast('应用失败：' + e.message)
  }
  applying.value = false
  refreshMode()
}

async function refreshMode() {
  try {
    const cur = await bridge.readFile(CUR_MODE_FILE)
    const m = cur ? cur.trim() : null
    curMode.value = (m && modeKeys.indexOf(m) >= 0) ? m : null
  } catch (e) { /* 忽略 */ }
}

// ===== 功耗统计 spark =====
async function refreshHist() {
  try {
    const vals = await bridge.recentWatts(180)
    drawSpark(vals || [])
    if (!vals || !vals.length) {
      histHint.value = '暂无记录 · 采样中'
    } else {
      let avg = 0, max = 0
      for (const v of vals) { avg += v; if (v > max) max = v }
      avg /= vals.length
      histHint.value = '近3小时 · 均值 ' + avg.toFixed(2) + ' W · 峰值 ' + max.toFixed(2) + ' W'
    }
  } catch (e) {
    histHint.value = '读取失败'
  }
}

function drawSpark(vals) {
  const cv = sparkCanvas.value
  if (!cv) return
  const dpr = window.devicePixelRatio || 1
  const w = cv.clientWidth || 300, h = 52
  cv.width = w * dpr; cv.height = h * dpr
  const ctx = cv.getContext('2d')
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  ctx.clearRect(0, 0, w, h)
  if (!vals || vals.length < 2) return
  let min = Infinity, max = -Infinity
  for (const v of vals) { if (v < min) min = v; if (v > max) max = v }
  if (max - min < 0.01) { max = min + 0.01 }
  const n = vals.length
  const px = i => (i / (n - 1)) * (w - 4) + 2
  const py = v => h - 6 - ((v - min) / (max - min)) * (h - 14)
  // 填充
  const grad = ctx.createLinearGradient(0, 0, 0, h)
  grad.addColorStop(0, 'rgba(0,150,200,.25)')
  grad.addColorStop(1, 'rgba(0,150,200,0)')
  ctx.beginPath()
  ctx.moveTo(px(0), py(vals[0]))
  for (let i = 1; i < n; i++) ctx.lineTo(px(i), py(vals[i]))
  ctx.lineTo(px(n - 1), h); ctx.lineTo(px(0), h); ctx.closePath()
  ctx.fillStyle = grad; ctx.fill()
  // 线
  ctx.beginPath()
  ctx.moveTo(px(0), py(vals[0]))
  for (let i = 1; i < n; i++) ctx.lineTo(px(i), py(vals[i]))
  ctx.strokeStyle = '#0096C8'; ctx.lineWidth = 1.8; ctx.lineJoin = 'round'; ctx.stroke()
  // 末点
  ctx.beginPath()
  ctx.arc(px(n - 1), py(vals[n - 1]), 2.6, 0, Math.PI * 2)
  ctx.fillStyle = '#0096C8'; ctx.fill()
}

function goRecords() {
  // 跳转功耗记录：由 App 层提供页签切换钩子（不可用时留在本页）
  try {
    if (window.__goRecords) window.__goRecords()
  } catch (e) { /* 忽略 */ }
}

// ===== CPU =====
async function refreshCpu(force) {
  if (force) await tick()
  try {
    if (!topo.value.length) {
      const t = await bridge.cpuTopology()
      topo.value = t || []
    }
  } catch (e) { /* 忽略 */ }
}

async function toggleCore(cpu) {
  if (cpuSwitching) return
  const c = cores.value[cpu]
  if (!c) return
  const on = !c.online
  cpuSwitching = true
  showToast('正在' + (on ? '启用' : '停用') + ' CPU' + cpu + ' …')
  try {
    const ok = await bridge.setCoreOnline(cpu, on)
    if (!ok) showToast('切换失败：需要 ROOT 或内核不支持热插拔')
  } catch (e) {
    showToast('切换失败')
  }
  cpuSwitching = false
  tick()   // 立即回读真实状态
}

function clusterLabel(cpu) {
  const t = topo.value
  if (!t || !t.length) return ''
  let idx = -1
  for (let i = 0; i < t.length; i++) {
    if (t[i][1] >= 0 && cpu >= t[i][1] && cpu <= t[i][2]) { idx = i; break }
  }
  if (idx < 0) return ''
  if (t.length === 1) return '全核'
  if (t.length === 2) return idx === 0 ? '小核' : '大核'
  return idx === 0 ? '小核' : (idx === t.length - 1 ? '大核' : '中核')
}

// ===== 电芯模式 =====
async function pickCellMode() {
  const opts = ['自动校准（当前识别: ' + (lastAutoCells >= 2 ? '双电芯' : '单电芯') + '）', '强制双电芯（电流×2）', '强制单电芯']
  const cur = cellMode === 2 ? 1 : (cellMode === 1 ? 2 : 0)
  const sel = await pickOptions(opts, cur, '电芯模式')
  if (sel < 0) return
  cellMode = sel === 1 ? 2 : (sel === 2 ? 1 : 0)
  try {
    await bridge.setCellMode(cellMode)
    peakW.value = 0   // 电芯模式切换后重计峰值
    showToast('已切换电芯模式')
  } catch (e) { /* 忽略 */ }
}

// ===== Root =====
async function syncRoot() {
  try {
    const rs = await bridge.rootState()
    if (rs) {
      rooted.value = !!rs.rooted
      rootChecked.value = !!rs.checked
      rootPending.value = !!rs.requesting
    }
  } catch (e) { /* 忽略 */ }
}

async function onRootBadge() {
  if (rooted.value || rootPending.value) return
  try {
    const r = await bridge.requestRoot()
    if (r && r.ok) {
      rooted.value = true; rootChecked.value = true; rootPending.value = false
      showToast('Root 授权成功')
      refreshMode()
    } else if (r && r.requesting) {
      rootPending.value = true
    }
  } catch (e) { /* 忽略 */ }
}

// ===== 悬浮窗 =====
async function syncOverlays() {
  try {
    const st = await bridge.overlayStates()
    if (st && st.length) overlays.value.forEach((o, i) => { o.on = !!st[i] })
  } catch (e) { /* 忽略 */ }
}

async function toggleOverlay(i) {
  const on = !overlays.value[i].on
  try {
    const r = await bridge.overlaySet(i, on)
    if (r && r.needPermission) {
      showToast('请先授予悬浮窗权限后重试')
      await bridge.requestOverlayPermission()
      return
    }
    overlays.value[i].on = on
  } catch (e) {
    showToast('操作失败')
  }
}

// ===== 生命周期 =====
onMounted(async () => {
  initRtChart()
  await Promise.all([syncRoot(), refreshMode(), syncOverlays(), refreshCpu(false)])
  try { socInfo.value = await bridge.soc() } catch (e) { /* 忽略 */ }
  try { cellMode = (await bridge.getCellMode()) || 0 } catch (e) { cellMode = 0 }
  startLoop()
})

onActivated(() => {
  syncRoot(); refreshMode(); syncOverlays(); refreshHist()
  startLoop()
})

onDeactivated(() => { stopLoop() })

onBeforeUnmount(() => {
  stopLoop()
  if (rtChart) { rtChart.destroy(); rtChart = null }
})
</script>
