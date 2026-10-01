import { reactive } from 'vue'
import { themeGet, themeSet, bgImage, onEvent } from './bridge'

/**
 * 主题状态：Vue 负责内容区视觉（页面底色/玻璃卡片/暗色令牌），
 * 原生负责窗口背景（自定义背景图渲染/透桌面/系统栏配色）。
 * imageBg / transparent 模式下 Vue 页面保持透明，透出原生窗口背景。
 */
export const theme = reactive({
  dark: false, transparent: false, imageBg: false,
  bgAlpha: 100, bgScale: 100, bgOffX: 0, bgOffY: 0,
  glass: 75, liquid: false,
  hasImage: false, bgUrl: null,
  loaded: false
})

function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)) }

/** 应用到 CSS 变量（styles.css 消费） */
export function applyTheme() {
  const root = document.documentElement
  root.classList.toggle('dark', !!theme.dark)
  const immersive = (theme.imageBg && theme.hasImage) || theme.transparent
  const g = immersive ? clamp(theme.glass, 30, 100) / 100 : 1
  // 页面底色：沉浸模式透明（透出原生窗口背景/壁纸），否则实色
  root.style.setProperty('--page-bg', immersive ? 'transparent' : (theme.dark ? '#0D1220' : '#F5F7FC'))
  // 玻璃卡片
  root.style.setProperty('--card-bg', theme.dark
    ? `rgba(22,29,47,${g})`
    : (g >= 1 ? '#FFFFFF' : `rgba(255,255,255,${g})`))
  root.style.setProperty('--nav-bg', theme.dark
    ? `rgba(13,18,32,${0.55 + g * 0.4})`
    : `rgba(255,255,255,${0.55 + g * 0.4})`)
  root.style.setProperty('--liquid', theme.liquid && immersive ? '1' : '0')
}

export async function initTheme() {
  try {
    const t = await themeGet()
    if (t) {
      theme.dark = !!t.dark
      theme.transparent = !!t.transparent
      theme.imageBg = !!t.imageBg
      theme.bgAlpha = t.bgAlpha
      theme.bgScale = t.bgScale
      theme.bgOffX = t.bgOffX
      theme.bgOffY = t.bgOffY
      theme.glass = t.glass
      theme.liquid = !!t.liquid
      theme.hasImage = !!t.hasImage
    }
  } catch (e) { /* 原生桥未就绪时用默认值 */ }
  theme.loaded = true
  applyTheme()
}

/** 修改主题项：本地生效 + 持久化到原生 */
export async function setTheme(key, value) {
  theme[key] = value
  applyTheme()
  try { await themeSet(key, value) } catch (e) { /* 忽略 */ }
}

/** 背景图变化（选择器/主题设置）后重取 dataURL */
export async function refreshBgImage() {
  try {
    const url = await bgImage()
    theme.bgUrl = url || null
    theme.hasImage = !!url
  } catch (e) {
    theme.bgUrl = null
  }
  applyTheme()
}

// 原生事件：背景图更换
onEvent(ev => {
  if (ev && ev.type === 'bgChanged') refreshBgImage()
  if (ev && ev.type === 'themeChanged') initTheme()
})
