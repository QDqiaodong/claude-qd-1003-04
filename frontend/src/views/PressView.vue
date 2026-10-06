<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import router from '../router'
import api from '../api'
import { createLoadGate } from '../utils/intakeQuery'
import {
  PRESS_ACTIONS,
  actionState,
  actionErrorMessage,
  createStationLocks,
  isMaintaining,
  maintainInfo,
  newRequestId
} from '../utils/pressControl'

// ------------------------------------------------------------------
// 页面操作范式：半圆负荷表 + 滑杆调负荷 + 运行/停机/检修状态切换。
//
// 一致性要点（与后端状态机、幂等接口配套）：
//  ① 服务器是唯一事实来源：每次操作成功后重新拉取，页面只展示服务器落下的状态；
//  ② 同一机组同一时刻只允许一个写操作在途（createStationLocks），重复点击复用
//     在途请求，不会发出第二个；调负荷与转检修不可能在页面侧同时发生；
//  ③ 每次操作带 requestId：失败 / 超时后的「重试」复用原键，后端幂等保证
//     只落一条一致结果，不会覆盖检修时间、不会重复推进状态；
//  ④ 操作失败不清状态、不乐观改数字，保留最新一次服务器状态并允许安全重试；
//  ⑤ 刷新页面从服务器还原：状态、当前负荷、最近检修时间、按钮可用性全部来自接口。
// ------------------------------------------------------------------

const routeQuery = computed(() => router.currentRoute.value.query || {})
const presses = ref([])
const loading = ref(false)
const error = ref('')
const tip = ref('')
const draft = ref({})

// 每个机组是否有写操作在途（按钮 / 滑杆据此禁用）
const busy = reactive({})
// 最近一次失败的操作意图：保留参数与 requestId，供「安全重试」复用
const failedIntent = ref(null)

const locks = createStationLocks()
const loadGate = createLoadGate()

const selectedId = ref(null)
const selected = computed(() => presses.value.find((p) => p.id === selectedId.value) || null)

// 加载序列闸 + 显式刷新标记：较早发出的慢响应不得覆盖最后一次拉取
async function load(silent = false) {
  const token = loadGate.next()
  if (!silent) loading.value = true
  try {
    const q = routeQuery.value.q
    const rows = await api.listPresses(q ? { status: q } : {})
    if (!loadGate.isCurrent(token)) return
    presses.value = rows
    const d = {}
    for (const p of rows) d[p.id] = p.currentLoadTph
    draft.value = d
    if (selectedId.value && !rows.some((p) => p.id === selectedId.value)) {
      selectedId.value = rows.length ? rows[0].id : null
    }
    if (!selectedId.value && rows.length) selectedId.value = rows[0].id
    error.value = ''
  } catch (e) {
    if (loadGate.isCurrent(token)) {
      // 拉取失败：保留已有列表（不清空、不用假数字），只提示可重试
      error.value = `状态加载失败：${e.message}；页面保留最近一次已确认状态，可点击「刷新」重试`
    }
  } finally {
    if (loadGate.isCurrent(token)) loading.value = false
  }
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
  return Number.isFinite(v) && v > Number(p.designCapacityTph)
}

function stateOf(p, action) {
  const draftLoad = action === 'setLoad' ? draft.value[p.id] : null
  return actionState(p, action, busy[p.id], draftLoad)
}

// 统一执行一个写操作意图；retryIntent 非空时复用原 requestId（安全重试）
async function execute(intent) {
  const stationId = intent.stationId
  // 同一机组已有操作在途：runExclusive 直接复用其 Promise，重复点击只会落一条结果
  const result = await locks.runExclusive(stationId, async () => {
    busy[stationId] = true
    failedIntent.value = null
    error.value = ''; tip.value = ''
    try {
      if (intent.action === 'setLoad') {
        await api.setLoad(stationId, intent.load, intent.requestId)
      } else {
        await api.transferPress(stationId, intent.target, intent.requestId)
      }
      return { ok: true, label: intent.label }
    } catch (e) {
      // 失败不乐观改状态：保留服务器最新状态，记下意图允许重试（requestId 不变）
      return { ok: false, error: e, intent }
    } finally {
      busy[stationId] = false
    }
  })

  if (result.ok) {
    tip.value = `${intent.stationName}：${intent.label}已生效`
    await load(true)
  } else {
    failedIntent.value = result.intent
    error.value = actionErrorMessage(result.error, result.intent.label)
  }
}

async function setLoad(p) {
  const st = actionState(p, 'setLoad', busy[p.id], draft.value[p.id])
  if (!st.enabled) return
  await execute({
    stationId: p.id,
    stationName: p.stationName,
    action: 'setLoad',
    load: draft.value[p.id],
    requestId: newRequestId(),
    label: `设定负荷 ${draft.value[p.id]} t/h`
  })
}

async function transfer(p, action) {
  const act = PRESS_ACTIONS[action]
  const st = actionState(p, action, busy[p.id])
  if (!st.enabled || !act) return
  await execute({
    stationId: p.id,
    stationName: p.stationName,
    action: 'transfer',
    target: act.target,
    requestId: newRequestId(),
    label: `转为「${act.target}」`
  })
}

// 安全重试：复用失败意图的 requestId——超时后服务端若已落库，会返回同一结果；
// 若未落库，这次正常执行。两种情况都只会落一条一致结果。
async function retry() {
  if (!failedIntent.value) return
  await execute({ ...failedIntent.value })
}

// 页面内对选中机组展示的动作集合（可用性由 actionState 按当前状态统一判定）
const actionButtons = [
  { key: 'setLoad', cls: 'act primary' },
  { key: 'stop', cls: 'act' },
  { key: 'start', cls: 'act' },
  { key: 'maintain', cls: 'act warn' },
  { key: 'finishMaintain', cls: 'act ok' }
]

watch(() => routeQuery.value.q, () => load())
onMounted(() => load())
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">压榨机组 · 负荷表</span>
        <span class="panel-desc">运行 ↔ 停机；运行 / 停机可转检修（负荷归零并记录检修时间），检修完成只能回停机</span>
        <button class="refresh-btn" :disabled="loading" @click="load()">
          {{ loading ? '刷新中…' : '刷新状态' }}
        </button>
      </div>

      <div v-if="error" class="err">
        <span>{{ error }}</span>
        <button v-if="failedIntent" class="retry-btn" @click="retry">重试上次操作</button>
        <button v-else class="retry-btn" @click="load()">重新加载</button>
      </div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">机组</div><div class="v">{{ presses.length }}</div></div>
        <div class="stat"><div class="k">运行</div><div class="v">{{ presses.filter(p => p.status === '运行').length }}</div></div>
        <div class="stat"><div class="k">检修</div><div class="v">{{ presses.filter(p => p.status === '检修').length }}</div></div>
      </div>

      <div class="gauge-row">
        <div v-for="p in presses" :key="p.id" class="gauge-card" :class="{ on: p.id === selectedId, maintaining: isMaintaining(p) }" @click="selectedId = p.id">
          <div class="g-name">{{ p.stationName }}</div>
          <div class="gauge">
            <div class="dial"></div>
            <div class="warn-zone"></div>
            <div class="needle" :style="{ transform: `rotate(${angle(p)}deg)` }"></div>
            <div class="hub"></div>
            <div class="g-val">{{ p.loadRate }}%</div>
          </div>
          <div class="g-meta">{{ p.currentLoadTph }} / {{ p.designCapacityTph }} t/h</div>
          <div class="g-status" :class="{ 's-maintain': isMaintaining(p) }">{{ p.status }}</div>
          <div class="g-maintain" :class="{ active: maintainInfo(p).active }">{{ maintainInfo(p).text }}</div>
          <div v-if="busy[p.id]" class="g-busy">操作进行中…</div>
        </div>
      </div>
    </div>

    <div class="panel" v-if="selected">
      <div class="panel-head">
        <span class="panel-title">操作 · {{ selected.stationName }}</span>
        <span class="panel-desc">
          当前状态「{{ selected.status }}」 · 当前负荷 {{ selected.currentLoadTph }} / {{ selected.designCapacityTph }} t/h
          · {{ maintainInfo(selected).text }}
        </span>
      </div>

      <div class="slider-row">
        <span class="s-label">负荷</span>
        <input type="range" min="0" :max="Math.ceil(Number(selected.designCapacityTph) * 1.2)" step="5"
               v-model.number="draft[selected.id]" class="slider"
               :disabled="busy[selected.id] || selected.status !== '运行'" />
        <span class="s-val" :class="{ over: isOver(selected.id) }">{{ draft[selected.id] }} t/h</span>
      </div>
      <div v-if="isOver(selected.id)" class="over-tip">已超过设计处理量，设定会被拦下</div>

      <div class="op-row">
        <template v-for="b in actionButtons" :key="b.key">
          <button :class="b.cls"
                  :disabled="!stateOf(selected, b.key).enabled || busy[selected.id]"
                  :title="stateOf(selected, b.key).reason"
                  @click="b.key === 'setLoad' ? setLoad(selected) : transfer(selected, b.key)">
            {{ PRESS_ACTIONS[b.key].label }}
          </button>
        </template>
      </div>
      <div class="hint-line">
        检修中的机组不能调负荷、不能开机、不能再次进入检修；按钮置灰时悬停可见原因。
      </div>
    </div>
  </div>
</template>

<style scoped>
.refresh-btn {
  margin-left: auto; padding: 4px 12px; font-size: 12px; border: 1px solid var(--primary-soft);
  border-radius: 6px; background: #fff; color: var(--primary-deep); cursor: pointer;
}
.refresh-btn:disabled { opacity: 0.6; cursor: default; }
.retry-btn {
  margin-left: 10px; padding: 2px 10px; font-size: 12px; border: 1px solid #c62828;
  border-radius: 6px; background: #fff; color: #c62828; cursor: pointer;
}

.gauge-row { display: flex; gap: 14px; flex-wrap: wrap; }

.gauge-card {
  width: 210px; padding: 10px; border: 1px solid var(--line); border-radius: 10px;
  cursor: pointer; background: #fff; text-align: center;
}
.gauge-card:hover { border-color: var(--primary-soft); }
.gauge-card.on { border-color: var(--primary); box-shadow: 0 0 0 2px var(--primary-wash); }
.gauge-card.maintaining { background: #fdf6ec; }

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
.g-status.s-maintain { color: #b07d00; }
.g-maintain { font-size: 10px; color: var(--muted); margin-top: 2px; line-height: 1.4; min-height: 14px; }
.g-maintain.active { color: #b07d00; font-weight: 700; }
.g-busy { font-size: 10px; color: var(--primary); margin-top: 2px; }

.slider-row { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
.s-label { font-size: 13px; color: var(--muted); width: 40px; }
.slider { flex: 1; accent-color: var(--primary); }
.slider:disabled { opacity: 0.5; cursor: not-allowed; }
.s-val { font-weight: 800; color: var(--primary-deep); width: 78px; text-align: right; }
.s-val.over { color: #c62828; }
.over-tip { font-size: 12px; color: #c62828; margin-bottom: 8px; }
.op-row { display: flex; gap: 8px; flex-wrap: wrap; }
.act.warn { border-color: #ffb300; color: #8a6100; }
.act.ok { border-color: #2e7d32; color: #2e7d32; }
.act:disabled { opacity: 0.45; cursor: not-allowed; }
.hint-line { margin-top: 8px; font-size: 12px; color: var(--muted); }
</style>
