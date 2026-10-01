<template>
  <div class="wrap">
    <header>
      <svg viewBox="0 0 108 108" style="width:38px;height:38px">
        <rect x="25" y="25" width="58" height="58" rx="12" fill="rgba(0,229,255,.04)" stroke="#00B8E0" stroke-width="3"/>
        <path d="M41 34v4M54 34v4M67 34v4M41 70v4M54 70v4M67 70v4M34 41h4M34 54h4M34 67h4M70 41h4M70 54h4M70 67h4" stroke="#93A7C4" stroke-width="3.4" stroke-linecap="round"/>
        <rect x="38" y="38" width="32" height="32" rx="5" fill="#2A63E8"/>
        <rect x="41" y="41" width="16" height="4" rx="2" fill="#fff" opacity=".3"/>
        <path d="M42 57h6l3.5-8 4.5 14 4-11 3 5h8" fill="none" stroke="#00E5FF" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>
        <path d="M48 45l4 7h-8z" fill="#FFC400"/>
        <circle cx="65" cy="46" r="5" fill="none" stroke="#FF9100" stroke-width="1.6"/>
        <path d="M65 42.5c1.4 1.8 2.7 2.6 2.7 4.2a2.7 2.7 0 1 1-5.4 0c0-1.6 1.3-2.4 2.7-4.2z" fill="#FF9100"/>
      </svg>
      <div><h1>Color管理器<small> · {{ subtitle }}</small></h1></div>
    </header>

    <keep-alive>
      <component :is="currentPage" />
    </keep-alive>
  </div>

  <nav class="tabbar">
    <div class="in">
      <button v-for="t in tabs" :key="t.key" :class="{on: tab===t.key}" @click="tab=t.key">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" v-html="t.icon"/>
        <span>{{ t.name }}</span>
      </button>
    </div>
  </nav>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { initTheme } from './theme'
import Home from './pages/Home.vue'
import Schedule from './pages/Schedule.vue'
import Governor from './pages/Governor.vue'
import Mode from './pages/Mode.vue'
import More from './pages/More.vue'

const tab = ref('home')
const pages = { home: Home, schedule: Schedule, governor: Governor, mode: Mode, more: More }
const currentPage = computed(() => pages[tab.value] || Home)
const subtitle = computed(() => {
  const t = tabs.find(x => x.key === tab.value)
  return t ? t.sub : ''
})

const icons = {
  home: '<path d="M3 10.5 12 3l9 7.5"/><path d="M5 9.5V21h14V9.5"/><path d="M9.5 21v-6h5v6"/>',
  schedule: '<rect x="4" y="4" width="16" height="16" rx="3"/><path d="M9 9h6M9 13h6M9 17h3"/>',
  governor: '<path d="M12 3a9 9 0 1 0 9 9"/><path d="M12 12l5-5"/><circle cx="12" cy="12" r="1.6"/>',
  mode: '<rect x="6" y="3" width="12" height="18" rx="3"/><circle cx="12" cy="17.5" r="1.4"/>',
  more: '<circle cx="5" cy="12" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="19" cy="12" r="1.6"/>'
}
const tabs = [
  { key: 'home', name: '主页', sub: '自适应限频', icon: icons.home },
  { key: 'schedule', name: '调度参数', sub: '调度参数', icon: icons.schedule },
  { key: 'governor', name: '调速器', sub: '调速器配置', icon: icons.governor },
  { key: 'mode', name: '应用策略', sub: '动态模式', icon: icons.mode },
  { key: 'more', name: '更多', sub: '主题与记录', icon: icons.more }
]

onMounted(() => {
  initTheme()
  // 供页面内跳转：功耗统计卡片 → 更多页（功耗记录）
  window.__goRecords = () => { tab.value = 'more' }
})
</script>
