<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import router from './router'
import api from './api'

import IntakeView from './views/IntakeView.vue'
import FeedView from './views/FeedView.vue'
import PressView from './views/PressView.vue'
import PanView from './views/PanView.vue'
import BatchView from './views/BatchView.vue'

// ------------------------------------------------------------------
// 导航形态：底部 chips 横滑导航条。
// 没有侧边栏也没有顶部菜单，整屏底部是一排可横向滑动的胶囊：
// 「甘蔗进厂」「投料交接」「压榨机组」「蒸发结晶」「成品糖」各占一枚，选中那枚抬起并填充主色。
// 页面主体一直贴着底部条上方铺开，切模块只动那一排胶囊。
// ------------------------------------------------------------------

const viewMap = {
  '/intake': IntakeView,
  '/feed': FeedView,
  '/press': PressView,
  '/pan': PanView,
  '/batch': BatchView
}

const chips = [
  { path: '/intake', label: '甘蔗进厂', key: 'intake' },
  { path: '/feed', label: '投料交接', key: 'feed' },
  { path: '/press', label: '压榨机组', key: 'press' },
  { path: '/pan', label: '蒸发结晶', key: 'pan' },
  { path: '/batch', label: '成品糖', key: 'batch' }
]

const currentPath = ref(router.currentRoute.value.path)
const counts = ref({ intake: 0, feed: 0, press: 0, pan: 0, batch: 0 })

const currentView = computed(() => viewMap[currentPath.value] || IntakeView)
const currentLabel = computed(() => {
  const c = chips.find((x) => x.path === currentPath.value)
  return c ? c.label : '甘蔗进厂'
})

async function loadCounts() {
  try {
    const [a, f, b, c, d] = await Promise.all([
      api.listIntakes(), api.listFeedBatches(), api.listPresses(), api.listPans(), api.listBatches()
    ])
    counts.value = { intake: a.length, feed: f.length, press: b.length, pan: c.length, batch: d.length }
  } catch (e) {
    // 计数失败不影响主流程
  }
}

function go(path) {
  router.push(path)
  currentPath.value = path
}

watch(() => router.currentRoute.value.path, (p) => { currentPath.value = p })
onMounted(async () => {
  // main.js 没有 app.use(router)，vue-router 不会自己跑首次导航，
  // 所以直接带 hash 打开页面时要手动触发一次，否则永远停在默认视图。
  const initial = window.location.hash ? window.location.hash.slice(1) : '/'
  await router.replace(initial)
  currentPath.value = router.currentRoute.value.path
  await loadCounts()
})
</script>

<template>
  <div class="sugar-app">
    <header class="topbar">
      <div>
        <div class="t-title">制糖厂 · 榨季压榨与结晶成品</div>
        <div class="t-sub">当前：{{ currentLabel }}</div>
      </div>
      <div class="t-right">sugar_refinery</div>
    </header>

    <main class="content">
      <component :is="currentView" />
    </main>

    <nav class="chips-bar">
      <div
        v-for="c in chips"
        :key="c.path"
        class="chip"
        :class="{ on: currentPath === c.path }"
        @click="go(c.path)"
      >
        {{ c.label }}
        <span class="cnt">{{ counts[c.key] }}</span>
      </div>
    </nav>
  </div>
</template>
