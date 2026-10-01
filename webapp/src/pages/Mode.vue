<template>
  <div>
    <div class="card">
      <div class="row-between">
        <b style="font-size:15px">应用策略</b>
        <button class="chip" :class="{ on: deleteMode }" style="font-family:inherit" @click="deleteMode = !deleteMode">
          <span class="dot" :style="{ background: deleteMode ? 'var(--ac)' : '#A02CF0' }"></span>{{ deleteMode ? '完成' : '删除' }}
        </button>
      </div>

      <div class="unavail" v-if="!confLoaded && loadFail" style="padding:16px 6px">
        <b>{{ loadFail }}</b><br>授予 Root 权限后重新进入本页重试
      </div>

      <template v-if="confLoaded">
        <div class="desc" style="margin:8px 2px 0">moren 是全局默认的意思，包名等号加模式（实时的）</div>

        <div class="row-between" style="background:var(--soft);border-radius:10px;padding:10px 12px;margin-top:8px;cursor:pointer" @click="pickTarget = { type: 'moren' }">
          <b style="font-size:13px">全局默认</b>
          <b style="font-size:13px" :style="{ color: modeColor(moren) }">{{ modeName(moren) }}</b>
        </div>

        <div v-for="r in rules" :key="r.pkg" style="background:var(--soft);border-radius:10px;margin-top:6px">
          <div class="row-between" style="padding:10px 12px;cursor:pointer" @click="toggleExpand(r.pkg)">
            <div style="flex:1;min-width:0">
              <b style="font-size:13px;display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ appName(r.pkg) }}</b>
              <i style="font-style:normal;display:block;color:var(--t3);font-size:11px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ r.pkg }}</i>
            </div>
            <b style="font-size:13px;margin-left:10px;flex:none" :style="{ color: modeColor(r.mode) }">{{ modeName(r.mode) }}</b>
            <button v-if="deleteMode" style="background:none;border:none;color:#E5484D;font-size:15px;font-weight:700;cursor:pointer;margin-left:10px;padding:2px 4px;flex:none" @click.stop="removeRule(r.pkg)">✕</button>
          </div>
          <div v-if="expandedPkg === r.pkg" style="display:flex;gap:8px;flex-wrap:wrap;padding:0 12px 11px">
            <button class="chip" style="font-family:inherit;color:var(--ac)" @click="pickTarget = { type: 'rule', pkg: r.pkg }">
              <span class="dot" style="background:var(--ac)"></span>模式选择
            </button>
            <button class="chip" style="font-family:inherit" :style="loadChipStyle(r.pkg, 0)" @click="openCap(r.pkg, true)">小核上限</button>
            <button class="chip" style="font-family:inherit" :style="loadChipStyle(r.pkg, 1)" @click="openCap(r.pkg, false)">大核上限</button>
          </div>
        </div>

        <div class="hint" v-if="!rules.length">暂无应用规则，点击下方“添加应用规则”新建</div>
      </template>

      <div style="display:flex;gap:10px;margin-top:13px">
        <button class="mini-btn" style="font-family:inherit;flex:1;padding:11px 0;color:#A02CF0;border-color:#A02CF0" @click="pickApp">添加应用规则</button>
        <button class="btn" style="flex:1;margin-top:0" @click="saveConf">{{ saving ? '保存中…' : '保存策略' }}</button>
      </div>
    </div>

    <!-- 底部上拉面板：选择应用 -->
    <div class="sheet-mask" v-if="sheetOpen" @click.self="sheetOpen = false">
      <div class="sheet">
        <div class="sheet-grab"></div>
        <div class="row-between" style="padding:8px 2px 2px">
          <b style="font-size:15px">选择应用</b>
          <button class="mini-btn ghost" style="font-family:inherit" @click="sheetOpen = false">关闭</button>
        </div>
        <input class="input" style="margin:8px 0 4px" v-model="appQuery" placeholder="搜索应用名或包名">
        <div class="unavail" v-if="appsLoading" style="padding:18px 6px">应用列表加载中…</div>
        <div class="unavail" v-else-if="appErr" style="padding:18px 6px"><b>应用列表读取失败</b><br>{{ appErr }}</div>
        <div class="unavail" v-else-if="!filteredApps.length" style="padding:18px 6px">未扫描到应用</div>
        <div v-for="a in filteredApps" :key="a.pkg" style="display:flex;align-items:center;gap:11px;padding:9px 2px;cursor:pointer" @click="onPickApp(a)">
          <div style="flex:1;min-width:0">
            <b style="font-size:13.5px;display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ a.label || a.pkg }}</b>
            <i style="font-style:normal;display:block;color:var(--t3);font-size:11px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">{{ a.pkg }}</i>
          </div>
          <span v-if="ruleMap[a.pkg]" style="flex:none;font-size:11.5px;font-weight:700;color:var(--ac);background:#E5F7FD;border-radius:9px;padding:3px 8px">{{ modeName(ruleMap[a.pkg]) }}</span>
        </div>
        <div class="hint" style="text-align:center">已配置规则的应用右侧带模式徽章</div>
      </div>
    </div>

    <!-- 选择调度模式 -->
    <div class="cf-mask" v-if="pickTarget" @click.self="pickTarget = null">
      <div class="cf-dialog">
        <div class="cf-dialog-title">选择调度模式</div>
        <div class="chips" style="margin:14px 0 4px">
          <button v-for="m in MODES" :key="m.key" class="chip" :class="{ on: pickerCur === m.key }" style="font-family:inherit" @click="onPickMode(m.key)">
            <span class="dot" :style="{ background: m.color }"></span>{{ m.name }}
          </button>
        </div>
        <div class="cf-dialog-btns">
          <button class="cf-dialog-btn cancel" style="font-family:inherit" @click="pickTarget = null">取消</button>
        </div>
      </div>
    </div>

    <!-- 小核/大核上限（滑条 + 数值框，选择即保存） -->
    <div class="cf-mask" v-if="capDlg" @click.self="capDlg = null">
      <div class="cf-dialog">
        <div class="cf-dialog-title">{{ capDlg.little ? '小核上限' : '大核上限' }}</div>
        <div class="cf-dialog-msg">{{ capDlg.msg }}</div>
        <div class="sl" style="padding:10px 0 2px">
          <input type="range" min="0" max="100" step="1" v-model.number="capDlg.val" :style="pctStyle(capDlg.val, 0, 100)">
          <input class="input" type="number" style="width:76px;flex:none;text-align:center" v-model.number="capDlg.val">
        </div>
        <div class="cf-dialog-btns">
          <button class="cf-dialog-btn cancel" style="font-family:inherit" @click="capDlg = null">取消</button>
          <button class="cf-dialog-btn ok" style="font-family:inherit" @click="confirmCap">确定</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onActivated } from 'vue'
import { readFile, writeFile, appList, appLimitEnsure } from '../bridge'
import { showToast } from '../ui'

/** 配置文件路径（与 ModeActivity 一致） */
const CONF_FILE = '/sdcard/Android/qingtd/动态模式切换.conf'
const LOAD_FILE = '/sdcard/Android/qingtd/单应用负载.conf'
/** 模块目录内的 conf 模板（service.sh 开机恢复源，保存时双写） */
const MODULE_CONF_FILE = '/data/adb/modules/colorFC/qingtd/动态模式切换.conf'

/** 模块四模式（powercfg.json 确认） */
const MODES = [
  { key: 'powersave', name: '省电', color: '#00B5A3' },
  { key: 'balance', name: '均衡', color: '#0096C8' },
  { key: 'performance', name: '性能', color: '#E08A00' },
  { key: 'fast', name: '极速', color: '#A02CF0' }
]

const moren = ref('powersave')
/** 应用规则：[{pkg, mode}]（保持文件行序，同包名更新不换位） */
const rules = ref([])
/** 单应用负载限制：pkg -> [小核上限%, 大核上限%]（0=未设；>100 为旧版绝对 MHz 兼容值） */
const loads = ref({})
const confLoaded = ref(false)
const loadFail = ref('')
const expandedPkg = ref(null)
const deleteMode = ref(false)
const saving = ref(false)

const sheetOpen = ref(false)
const appQuery = ref('')
const apps = ref([])
const appsLoading = ref(false)
const appErr = ref('')
const labelMap = ref({})

/** 模式选择弹窗目标：{type:'moren'} | {type:'rule', pkg, toastLabel} */
const pickTarget = ref(null)
/** 负载上限弹窗：{pkg, little, raw, val, msg} */
const capDlg = ref(null)

let skipFirstActivate = true

const ruleMap = computed(() => {
  const m = {}
  for (const r of rules.value) m[r.pkg] = r.mode
  return m
})

const pickerCur = computed(() => {
  const t = pickTarget.value
  if (!t) return ''
  return t.type === 'moren' ? moren.value : (ruleMap.value[t.pkg] || '')
})

const filteredApps = computed(() => {
  const q = appQuery.value.trim().toLowerCase()
  if (!q) return apps.value
  return apps.value.filter(a =>
    String(a.label || '').toLowerCase().includes(q) || String(a.pkg).toLowerCase().includes(q))
})

// ==================== 状态加载 ====================

async function loadState() {
  try {
    const pair = await Promise.all([readFile(CONF_FILE), readFile(LOAD_FILE)])
    parseLoads(pair[1])
    if (pair[0] != null) {
      parseConf(pair[0])
      confLoaded.value = true
      loadFail.value = ''
    } else {
      loadFail.value = '未读取到模块配置文件（动态模式切换.conf）'
    }
  } catch (e) {
    loadFail.value = '配置读取失败：' + (e?.message || e)
  }
}

/** 解析 动态模式切换.conf：moren=全局默认 / 包名=模式 */
function parseConf(conf) {
  moren.value = 'powersave'
  rules.value = []
  for (let line of String(conf).split('\n')) {
    line = line.trim()
    if (!line || line.startsWith('#')) continue
    const eq = line.indexOf('=')
    if (eq <= 0) continue
    const key = line.slice(0, eq).trim()
    const val = line.slice(eq + 1).trim()
    if (key === 'moren') moren.value = val
    else setRule(key, val)
  }
}

function setRule(pkg, mode) {
  const r = rules.value.find(x => x.pkg === pkg)
  if (r) r.mode = mode
  else rules.value.push({ pkg, mode })
}

/** 解析 单应用负载.conf（包名=小核上限%,大核上限%；旧版三段格式忽略第三段） */
function parseLoads(conf) {
  loads.value = {}
  if (conf == null) return
  for (let line of String(conf).split('\n')) {
    line = line.trim()
    if (!line || line.startsWith('#')) continue
    const eq = line.indexOf('=')
    if (eq <= 0) continue
    const v = line.slice(eq + 1).trim().split(',')
    loads.value[line.slice(0, eq).trim()] = [parseNum(v[0]), parseNum(v[1])]
  }
}

function parseNum(s) {
  const n = parseInt(s, 10)
  return isFinite(n) ? n : 0
}

// ==================== 保存 ====================

function buildConf() {
  let sb = '#moren是全局默认的意思，包名等号加模式（实时的）\n'
  sb += '#模式powersave、balance、performance、fast\n'
  sb += 'moren=' + moren.value + '\n'
  for (const r of rules.value) sb += r.pkg + '=' + r.mode + '\n'
  return sb
}

/** 选择即保存：写入 单应用负载.conf（失败必须告知，否则界面新值、服务仍执行旧配置） */
async function writeLoads() {
  let sb = '#单应用负载：包名=小核上限%,大核上限%（0=未设；旧版绝对MHz值>100兼容）\n'
  for (const pkg of Object.keys(loads.value)) {
    const v = loads.value[pkg]
    if (!(v[0] > 0) && !(v[1] > 0)) continue
    sb += pkg + '=' + v[0] + ',' + v[1] + '\n'
  }
  try {
    const r = await writeFile(sb, LOAD_FILE)
    if (!r || !r.ok) {
      showToast('配置写入失败：' + ((r && r.err) || 'root 写入失败') + '，限制设置未保存')
    }
  } catch (e) {
    showToast('配置写入失败：' + (e?.message || e) + '，限制设置未保存')
  }
}

async function saveConf() {
  if (!confLoaded.value) {
    showToast('配置尚未加载完成')
    return
  }
  if (saving.value) return
  saving.value = true
  const content = buildConf()
  try {
    // 双写: 工作文件 + 模块目录模板（service.sh 可能从模块模板恢复 conf，只写工作文件会被回滚）
    const r1 = await writeFile(content, CONF_FILE)
    if (!r1 || !r1.ok) {
      showToast('保存失败：' + ((r1 && r1.err) || 'root 写入失败'))
      return
    }
    try { await writeFile(content, MODULE_CONF_FILE) } catch (e) { /* 模板写入失败不阻断（模块可能未安装） */ }
    try { await appLimitEnsure() } catch (e) { /* 原生侧自行兜底 */ }
    // 写后 2 秒回读验证是否被外部回滚（对齐原生）
    await sleep(2000)
    let stable = false
    try {
      const back = await readFile(CONF_FILE)
      stable = back != null && back.trim() === content.trim()
    } catch (e) { /* 回读失败按不稳定处理 */ }
    showToast(stable ? '保存成功' : '保存后被系统回滚，请把此提示截图反馈')
    await loadState()
  } catch (e) {
    showToast('保存失败：' + (e?.message || e))
  } finally {
    saving.value = false
  }
}

const sleep = ms => new Promise(r => setTimeout(r, ms))

// ==================== 规则交互 ====================

function toggleExpand(pkg) {
  expandedPkg.value = expandedPkg.value === pkg ? null : pkg
}

function onPickMode(key) {
  const t = pickTarget.value
  pickTarget.value = null
  if (!t) return
  if (t.type === 'moren') {
    moren.value = key
    showToast('全局默认已设为 ' + modeName(key) + '\n记得保存策略')
  } else {
    setRule(t.pkg, key)
    if (t.toastLabel) showToast('已添加：' + t.toastLabel + ' → ' + modeName(key) + '\n记得保存策略')
  }
}

async function removeRule(pkg) {
  const i = rules.value.findIndex(x => x.pkg === pkg)
  if (i >= 0) rules.value.splice(i, 1)
  if (loads.value[pkg]) delete loads.value[pkg]
  if (expandedPkg.value === pkg) expandedPkg.value = null
  await writeLoads()
}

// ==================== 负载上限 ====================

function openCap(pkg, little) {
  const ld = loads.value[pkg]
  const raw = ld ? (little ? ld[0] : ld[1]) : 0
  capDlg.value = {
    pkg,
    little,
    raw,
    val: Math.min(100, Math.max(0, raw)),
    msg: raw > 100
      ? '当前为旧版绝对 MHz 值（' + raw + '），保存后将转为百分比上限'
      : '按本机各簇最高频率的百分比封顶，0 = 不限制'
  }
}

/** idx: 0=小核上限% 1=大核上限%；两项均未设则移除条目（对齐原生 setLoad） */
async function confirmCap() {
  const d = capDlg.value
  capDlg.value = null
  if (!d) return
  const n = Math.round(Number(d.val))
  const v = Math.min(100, Math.max(0, isFinite(n) ? n : 0))
  const ld = loads.value[d.pkg] ? loads.value[d.pkg].slice() : [0, 0]
  ld[d.little ? 0 : 1] = v
  if (!(ld[0] > 0) && !(ld[1] > 0)) delete loads.value[d.pkg]
  else loads.value[d.pkg] = ld
  await writeLoads()
  appLimitEnsure().catch(() => {})   // 确保限制执行服务在跑（配置清空则服务自动退出）
}

function loadChipStyle(pkg, idx) {
  const on = (loads.value[pkg]?.[idx] || 0) > 0
  return on ? { color: '#E8A33D', background: 'rgba(232,163,61,.12)' } : { color: '#7C8AA0' }
}

// ==================== 应用选择 ====================

function pickApp() {
  if (!confLoaded.value) {
    showToast('配置尚未加载完成')
    return
  }
  appQuery.value = ''
  sheetOpen.value = true
  if (!apps.value.length && !appsLoading.value) fetchApps()
}

async function fetchApps() {
  if (appsLoading.value) return
  appsLoading.value = true
  appErr.value = ''
  try {
    const list = await appList()
    apps.value = (Array.isArray(list) ? list : []).filter(a => a && a.pkg)
    apps.value.sort((a, b) =>
      String(a.label || '').toLowerCase() < String(b.label || '').toLowerCase() ? -1 : 1)
    const m = {}
    for (const a of apps.value) m[a.pkg] = a.label || a.pkg
    labelMap.value = m
  } catch (e) {
    appErr.value = e?.message || '应用列表读取失败'
  }
  appsLoading.value = false
}

function onPickApp(a) {
  sheetOpen.value = false
  pickTarget.value = { type: 'rule', pkg: a.pkg, toastLabel: a.label || a.pkg }
}

// ==================== 工具 ====================

function modeName(key) {
  const m = MODES.find(x => x.key === key)
  if (m) return m.name
  return key ? key : '未知'
}

function modeColor(key) {
  const m = MODES.find(x => x.key === key)
  return m ? m.color : 'var(--t2)'
}

function appName(pkg) {
  return labelMap.value[pkg] || pkg
}

/** 滑条进度填充（styles.css input[type=range] 消费 --p） */
function pctStyle(v, min, max) {
  return { '--p': ((v - min) / (max - min) * 100) + '%' }
}

onMounted(() => {
  loadState()
  fetchApps()   // 供规则行显示应用名与选择器列表
})

onActivated(() => {
  if (skipFirstActivate) { skipFirstActivate = false; return }
  loadState()   // 重新可见时刷新（配置可能被外部脚本改动）
})
</script>
