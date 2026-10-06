<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import api from '../api'

// ------------------------------------------------------------------
// 页面操作范式：双列配对交接台。
// 左列是「已验收」磅单卡（余量为零的灰掉不可点），右列是压榨机组卡
// （非「运行」的灰掉不可点），各点一张完成配对；交接条同时亮出三个关键数——
// 磅单可投净重、机组剩余处理能力、机组已占用，计划量超过任一侧立即变红并锁死提交。
// 下方投料批看板推进状态：待投料 → 投料中 → 已完成；待投料可撤回（归还两侧），
// 投料中不可撤回、不可改机组，完成时录入实际量，少投差额自动释放回两侧。
// ------------------------------------------------------------------

const intakes = ref([])   // 已验收磅单（含可投余量）
const presses = ref([])   // 全部机组（含已占用 / 剩余能力）
const batches = ref([])   // 投料批
const error = ref('')
const tip = ref('')
const submitting = ref(false)

const intakeId = ref(null)
const stationId = ref(null)
const plannedKg = ref(null)
const actualDraft = ref({})

// 幂等键：一次「提交意图」一个键。表单内容变化或提交成功后重新生成；
// 重复点击 / 超时重试带同一键，后端只落一笔有效占用
const requestId = ref(newRequestId())

function newRequestId() {
  return window.crypto && crypto.randomUUID
    ? crypto.randomUUID()
    : String(Date.now()) + '-' + Math.random().toString(16).slice(2)
}

watch([intakeId, stationId, plannedKg], () => { requestId.value = newRequestId() })

const intake = computed(() => intakes.value.find((i) => i.id === intakeId.value) || null)
const station = computed(() => presses.value.find((p) => p.id === stationId.value) || null)

const overIntake = computed(() =>
  intake.value && Number(plannedKg.value) > 0 && Number(plannedKg.value) > Number(intake.value.availableKg))
const overStation = computed(() =>
  station.value && Number(plannedKg.value) > 0 && Number(plannedKg.value) > Number(stationKg(station.value)))

function stationKg(p) { return Number(p.remainingKg) }

const canSubmit = computed(() =>
  !submitting.value && intake.value && station.value &&
  Number(plannedKg.value) > 0 && !overIntake.value && !overStation.value)

function countOf(s) { return batches.value.filter((b) => b.status === s).length }

async function load() {
  error.value = ''
  try {
    const [a, b, c] = await Promise.all([
      api.listIntakes({ status: '已验收' }), api.listPresses(), api.listFeedBatches()
    ])
    intakes.value = a
    presses.value = b
    batches.value = c
    // 刷新后若选中项已不可交接（余量归零 / 机组停出运行），自动松开，避免拿着旧余量提交
    if (intakeId.value && !intakes.value.some((i) => i.id === intakeId.value && Number(i.availableKg) > 0)) {
      intakeId.value = null
    }
    if (stationId.value && !presses.value.some((p) => p.id === stationId.value && p.status === '运行')) {
      stationId.value = null
    }
  } catch (e) { error.value = e.message }
}

function fail(e) {
  const m = e && e.message ? e.message : '操作失败'
  // 网络 / 超时类失败：后端单事务保证不落半成品，同一 requestId 重试不会重复扣减
  if (/timeout|Network Error/.test(m)) {
    error.value = m + '；如不确定是否提交成功，可直接重试（同一请求幂等，不会重复扣减），或刷新列表确认'
  } else {
    error.value = m
  }
}

function pickIntake(i) {
  if (Number(i.availableKg) > 0) intakeId.value = i.id
}

function pickStation(p) {
  if (p.status === '运行') stationId.value = p.id
}

async function submit() {
  error.value = ''; tip.value = ''
  submitting.value = true
  try {
    const b = await api.createFeedBatch({
      intakeId: intakeId.value,
      stationId: stationId.value,
      plannedKg: Number(plannedKg.value),
      requestId: requestId.value
    })
    tip.value = `投料批 ${b.batchNo} 已交接：${b.ticketNo} → ${b.stationName}，计划 ${b.plannedKg} kg`
    plannedKg.value = null
    requestId.value = newRequestId()
    await load()
  } catch (e) { fail(e) } finally { submitting.value = false }
}

async function start(b) {
  error.value = ''; tip.value = ''
  try {
    await api.startFeedBatch(b.id)
    tip.value = `${b.batchNo} 已开始投料，此后不可撤回、不可改机组`
    await load()
  } catch (e) { fail(e) }
}

async function cancel(b) {
  error.value = ''; tip.value = ''
  try {
    await api.cancelFeedBatch(b.id)
    tip.value = `${b.batchNo} 已撤回，${b.plannedKg} kg 已归还磅单余量与机组能力`
    await load()
  } catch (e) { fail(e) }
}

async function complete(b) {
  error.value = ''; tip.value = ''
  const v = actualDraft.value[b.id]
  const actual = (v === undefined || v === null || v === '') ? Number(b.plannedKg) : Number(v)
  try {
    await api.completeFeedBatch(b.id, actual)
    const diff = Number(b.plannedKg) - actual
    tip.value = diff > 0
      ? `${b.batchNo} 已完成，实际 ${actual} kg，少投 ${diff} kg 已释放回两侧余量`
      : `${b.batchNo} 已完成，实际 ${actual} kg`
    await load()
  } catch (e) { fail(e) }
}

// 占用条百分比
function pct(used, total) {
  const t = Number(total) || 1
  return Math.max(0, Math.min(100, (Number(used) / t) * 100)) + '%'
}

onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">投料交接 · 配对交接台</span>
        <span class="panel-desc">左点磅单、右点机组；三个关键数同时亮出，任一侧不足即锁死提交</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">待投料</div><div class="v">{{ countOf('待投料') }}</div></div>
        <div class="stat"><div class="k">投料中</div><div class="v">{{ countOf('投料中') }}</div></div>
        <div class="stat"><div class="k">已完成</div><div class="v">{{ countOf('已完成') }}</div></div>
        <div class="stat"><div class="k">已撤回</div><div class="v">{{ countOf('已撤回') }}</div></div>
      </div>

      <div class="pair-row">
        <div class="col-box">
          <div class="col-title">已验收磅单（余量为零不可交接）</div>
          <div
            v-for="i in intakes" :key="i.id"
            class="pick" :class="{ on: i.id === intakeId, off: !(Number(i.availableKg) > 0) }"
            @click="pickIntake(i)"
          >
            <div class="p-line1">
              <span class="p-no">{{ i.ticketNo }}</span>
              <span class="p-avail">可投 {{ i.availableKg }} kg</span>
            </div>
            <div class="bar"><div class="bar-used" :style="{ width: pct(i.allocatedKg, i.netKg) }"></div></div>
            <div class="p-line2">净重 {{ i.netKg }} kg · 已交接 {{ i.allocatedKg }} kg</div>
          </div>
          <div v-if="!intakes.length" class="empty">暂无已验收磅单</div>
        </div>

        <div class="col-box">
          <div class="col-title">压榨机组（仅「运行」可接收投料）</div>
          <div
            v-for="p in presses" :key="p.id"
            class="pick" :class="{ on: p.id === stationId, off: p.status !== '运行' }"
            @click="pickStation(p)"
          >
            <div class="p-line1">
              <span class="p-no">{{ p.stationName }}</span>
              <span class="p-avail">剩余 {{ p.remainingTon }} t</span>
            </div>
            <div class="bar"><div class="bar-used" :style="{ width: pct(p.occupiedKg, p.designCapacityTph * 1000) }"></div></div>
            <div class="p-line2">设计 {{ p.designCapacityTph }} t/h · 已占用 {{ p.occupiedTon }} t · {{ p.status }}</div>
          </div>
          <div v-if="!presses.length" class="empty">暂无机组</div>
        </div>
      </div>

      <div class="deal-bar">
        <div class="kpi">
          <div class="k">磅单可投净重</div>
          <div class="v">{{ intake ? intake.availableKg + ' kg' : '—' }}</div>
        </div>
        <div class="kpi">
          <div class="k">机组剩余处理能力</div>
          <div class="v">{{ station ? station.remainingTon + ' t' : '—' }}</div>
        </div>
        <div class="kpi">
          <div class="k">机组已占用</div>
          <div class="v">{{ station ? station.occupiedTon + ' t' : '—' }}</div>
        </div>
        <input v-model.number="plannedKg" class="fld" style="width:140px" placeholder="计划投料量 kg" />
        <button class="act primary" :disabled="!canSubmit" @click="submit">
          {{ submitting ? '提交中…' : '提交交接' }}
        </button>
      </div>
      <div v-if="overIntake" class="warn-tip">超过磅单可投余量（{{ intake.availableKg }} kg），请调低</div>
      <div v-if="overStation" class="warn-tip">超过机组剩余处理能力（{{ station.remainingKg }} kg），请调低</div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">投料批看板</span>
        <span class="panel-desc">待投料可撤回（归还两侧）；投料中不可撤回、不可改机组；完成时少投差额自动释放</span>
      </div>
      <div class="fb-list">
        <div v-for="b in batches" :key="b.id" class="fb-row">
          <span class="fb-no">{{ b.batchNo }}</span>
          <span class="fb-route">{{ b.ticketNo }} → {{ b.stationName }}</span>
          <span class="fb-kg">计划 {{ b.plannedKg }} kg</span>
          <span v-if="b.status === '已完成'" class="fb-kg">实际 {{ b.actualKg }} kg</span>
          <span class="fb-st" :class="'st-' + b.status">{{ b.status }}</span>
          <template v-if="b.status === '投料中'">
            <input v-model.number="actualDraft[b.id]" class="fld n" :placeholder="'实际 kg（默认 ' + b.plannedKg + '）'" style="width:150px" />
            <button class="act sm primary" @click="complete(b)">完成</button>
          </template>
          <template v-if="b.status === '待投料'">
            <button class="act sm primary" @click="start(b)">开始投料</button>
            <button class="act sm" @click="cancel(b)">撤回</button>
          </template>
        </div>
        <div v-if="!batches.length" class="empty">暂无投料批</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.pair-row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 12px; }
@media (max-width: 900px) { .pair-row { grid-template-columns: 1fr; } }

.col-box { border: 1px solid var(--line); border-radius: 9px; padding: 10px; }
.col-title { font-size: 12px; font-weight: 700; color: var(--muted); margin-bottom: 8px; }

.pick {
  border: 1px solid var(--line); border-radius: 8px; padding: 8px 10px;
  margin-bottom: 8px; cursor: pointer; background: #fff;
}
.pick:hover { border-color: var(--primary-soft); }
.pick.on { border-color: var(--primary); box-shadow: 0 0 0 2px var(--primary-wash); }
.pick.off { opacity: 0.45; cursor: not-allowed; }

.p-line1 { display: flex; justify-content: space-between; align-items: center; }
.p-no { font-weight: 800; font-size: 13px; color: var(--primary-deep); }
.p-avail { font-size: 12px; font-weight: 700; color: var(--primary-deep); }

.bar { height: 7px; border-radius: 4px; background: #eef1dc; margin: 6px 0 4px; overflow: hidden; }
.bar-used { height: 100%; background: linear-gradient(90deg, var(--primary-soft), var(--primary)); }

.p-line2 { font-size: 11px; color: var(--muted); }
.empty { font-size: 12px; color: var(--muted); padding: 8px 2px; }

.deal-bar {
  display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
  border: 1px dashed var(--primary-soft); border-radius: 9px;
  background: var(--primary-wash); padding: 10px 12px;
}
.kpi { min-width: 130px; }
.kpi .k { font-size: 11px; color: var(--primary-deep); }
.kpi .v { font-size: 17px; font-weight: 800; color: var(--primary-deep); }

.warn-tip { font-size: 12px; color: #c62828; margin-top: 8px; }

.fb-list { display: flex; flex-direction: column; gap: 5px; }
.fb-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 6px 10px; border: 1px solid var(--line); border-radius: 8px; font-size: 12px; }
.fb-no { font-weight: 800; color: var(--primary-deep); width: 118px; }
.fb-route { color: var(--ink); width: 200px; }
.fb-kg { color: var(--muted); width: 118px; }

.fb-st { font-size: 11px; font-weight: 800; padding: 1px 8px; border-radius: 9px; }
.fb-st.st-待投料 { background: #fff8e1; color: #f57f17; }
.fb-st.st-投料中 { background: #e3f2fd; color: #1565c0; }
.fb-st.st-已完成 { background: #e8f5e9; color: #2e7d32; }
.fb-st.st-已撤回 { background: #eceff1; color: #607d8b; }
</style>
