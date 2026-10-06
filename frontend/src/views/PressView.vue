<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import router from '../router'
import api from '../api'

// ------------------------------------------------------------------
// 页面操作范式：半圆负荷表 + 滑杆调负荷。
// 每台压榨机组是一枚半圆仪表：指针指当前负荷，90%~100% 区间刷成琥珀色，
// 超过 100% 的刻度是红区（但系统根本不允许调进去）。
// 表下方一根滑杆直接拖负荷，拖到超过设计处理量时数字变红、「设定负荷」按钮置灰。
// ------------------------------------------------------------------

const routeQuery = computed(() => router.currentRoute.value.query || {})
const presses = ref([])
const error = ref('')
const tip = ref('')
const draft = ref({})

const selectedId = ref(null)
const selected = computed(() => presses.value.find((p) => p.id === selectedId.value) || null)

async function load() {
  error.value = ''
  try {
    const q = routeQuery.value.q
    presses.value = await api.listPresses(q ? { status: q } : {})
    if (!presses.value.length && q) presses.value = await api.listPresses()
    const d = {}
    for (const p of presses.value) d[p.id] = p.currentLoadTph
    draft.value = d
    if (!selectedId.value && presses.value.length) selectedId.value = presses.value[0].id
  } catch (e) { error.value = e.message }
}

// 指针角度：0 → -90°，100% → +90°（超载超出部分钳到红区边缘）
function angle(p) {
  const rate = Number(p.loadRate) || 0
  const clamped = Math.min(115, rate)
  return -90 + (clamped / 100) * 180
}

function isOver(id) {
  const p = presses.value.find((x) => x.id === id)
  if (!p) return false
  const v = Number(draft.value[id])
  return v > Number(p.designCapacityTph)
}

async function setLoad(p) {
  error.value = ''; tip.value = ''
  try {
    await api.setLoad(p.id, draft.value[p.id])
    tip.value = `${p.stationName} 负荷已设为 ${draft.value[p.id]} 吨/小时`
    await load()
  } catch (e) { error.value = e.message }
}

async function transfer(p, target) {
  error.value = ''; tip.value = ''
  try {
    await api.transferPress(p.id, target)
    tip.value = `${p.stationName} 已转为「${target}」`
    await load()
  } catch (e) { error.value = e.message }
}

const maxLoad = computed(() => {
  let m = 1
  for (const p of presses.value) m = Math.max(m, Number(p.designCapacityTph))
  return m
})

watch(() => routeQuery.value.q, () => load())
onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">压榨机组 · 负荷表</span>
        <span class="panel-desc">指针指当前负荷，琥珀区 90%~100%；负荷不允许超过设计处理量</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">机组</div><div class="v">{{ presses.length }}</div></div>
        <div class="stat"><div class="k">运行</div><div class="v">{{ presses.filter(p => p.status === '运行').length }}</div></div>
        <div class="stat"><div class="k">超载</div><div class="v">{{ presses.filter(p => p.isOverload).length }}</div></div>
      </div>

      <div class="gauge-row">
        <div v-for="p in presses" :key="p.id" class="gauge-card" :class="{ on: p.id === selectedId }" @click="selectedId = p.id">
          <div class="g-name">{{ p.stationName }}</div>
          <div class="gauge">
            <div class="dial"></div>
            <div class="warn-zone"></div>
            <div class="needle" :style="{ transform: `rotate(${angle(p)}deg)` }"></div>
            <div class="hub"></div>
            <div class="g-val">{{ p.loadRate }}%</div>
          </div>
          <div class="g-meta">{{ p.currentLoadTph }} / {{ p.designCapacityTph }} t/h</div>
          <div class="g-status">{{ p.status }}</div>
        </div>
      </div>
    </div>

    <div class="panel" v-if="selected">
      <div class="panel-head">
        <span class="panel-title">调负荷 · {{ selected.stationName }}</span>
        <span class="panel-desc">设计处理量 {{ selected.designCapacityTph }} 吨/小时</span>
      </div>

      <div class="slider-row">
        <span class="s-label">负荷</span>
        <input type="range" min="0" :max="Math.ceil(Number(selected.designCapacityTph) * 1.2)" step="5"
               v-model.number="draft[selected.id]" class="slider" />
        <span class="s-val" :class="{ over: isOver(selected.id) }">{{ draft[selected.id] }} t/h</span>
      </div>
      <div v-if="isOver(selected.id)" class="over-tip">已超过设计处理量，设定会被拦下</div>

      <div class="op-row">
        <button class="act primary" :disabled="isOver(selected.id) || selected.status !== '运行'" @click="setLoad(selected)">设定负荷</button>
        <button class="act" :disabled="selected.status !== '运行'" @click="transfer(selected, '停机')">停机</button>
        <button class="act" :disabled="selected.status !== '停机'" @click="transfer(selected, '运行')">开机</button>
        <button class="act" @click="transfer(selected, '检修')">转检修</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.gauge-row { display: flex; gap: 14px; flex-wrap: wrap; }

.gauge-card {
  width: 190px; padding: 10px; border: 1px solid var(--line); border-radius: 10px;
  cursor: pointer; background: #fff; text-align: center;
}
.gauge-card:hover { border-color: var(--primary-soft); }
.gauge-card.on { border-color: var(--primary); box-shadow: 0 0 0 2px var(--primary-wash); }

.g-name { font-weight: 800; color: var(--primary-deep); font-size: 13px; }

.gauge { position: relative; width: 160px; height: 88px; margin: 6px auto 2px; }
.dial {
  position: absolute; left: 10px; right: 10px; top: 8px; height: 80px;
  border: 10px solid #e9eddb; border-bottom: none;
  border-radius: 80px 80px 0 0; box-sizing: border-box;
}
/* 90%~100% 琥珀区 */
.warn-zone {
  position: absolute; left: 10px; right: 10px; top: 8px; height: 80px;
  border: 10px solid transparent; border-bottom: none; border-radius: 80px 80px 0 0;
  box-sizing: border-box;
  background: conic-gradient(from 270deg at 50% 100%, transparent 0deg, transparent 162deg, #ffb300 162deg, #ffb300 180deg, transparent 180deg);
  -webkit-mask: radial-gradient(circle at 50% 100%, transparent 58px, #000 59px);
          mask: radial-gradient(circle at 50% 100%, transparent 58px, #000 59px);
}
.needle {
  position: absolute; left: 50%; bottom: 8px; width: 3px; height: 62px;
  background: var(--primary-deep); transform-origin: bottom center;
  border-radius: 2px; transition: transform 0.3s;
}
.hub {
  position: absolute; left: 50%; bottom: 2px; width: 12px; height: 12px;
  margin-left: -6px; border-radius: 50%; background: var(--primary-deep);
}
.g-val { position: absolute; left: 0; right: 0; bottom: 14px; font-size: 15px; font-weight: 800; color: var(--primary-deep); }

.g-meta { font-size: 12px; color: var(--muted); }
.g-status { font-size: 11px; color: var(--primary-soft); font-weight: 700; }

.slider-row { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
.s-label { font-size: 13px; color: var(--muted); width: 40px; }
.slider { flex: 1; accent-color: var(--primary); }
.s-val { font-weight: 800; color: var(--primary-deep); width: 78px; text-align: right; }
.s-val.over { color: #c62828; }
.over-tip { font-size: 12px; color: #c62828; margin-bottom: 8px; }
.op-row { display: flex; gap: 8px; flex-wrap: wrap; }
</style>
