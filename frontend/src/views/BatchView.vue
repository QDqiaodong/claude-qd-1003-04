<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import router from '../router'
import api from '../api'

// ------------------------------------------------------------------
// 页面操作范式：糖仓堆叠块 + 批次复检 / 流转表。
// 新批次先进入「待复检」；值班员按当前版本提交糖度、纯度、纤维分和结论。
// 复检通过后入库按钮解锁；已入库且当前版本仍通过，发运按钮才解锁。
// requestId 固定一次提交意图，重复点击 / 超时重试只保留一条复检记录。
// ------------------------------------------------------------------

const routeQuery = computed(() => router.currentRoute.value.query || {})
const batches = ref([])
const stock = ref([])
const error = ref('')
const tip = ref('')
const flipped = ref([])
const submitting = ref(false)
const targetId = ref(null)
const filters = ref({ status: '', inspectionStatus: '' })
const form = ref({
  batchNo: '', quantityKg: 30000, warehouse: '1号糖仓',
  quality: { brix: 99.8, purity: 99.6, fiber: 0 }
})
const inspectForm = ref(emptyInspectForm())
const requestId = ref(newRequestId())

const WAREHOUSES = ['1号糖仓', '2号糖仓', '3号糖仓']
const BATCH_STATUSES = ['待复检', '已入库', '已发运']
const INSPECTION_STATUSES = ['待复检', '通过', '不通过']

const byWh = computed(() => {
  const m = {}
  for (const w of WAREHOUSES) {
    m[w] = batches.value.filter((b) => b.warehouse === w && b.status !== '已发运')
  }
  return m
})

const targetBatch = computed(() => batches.value.find((b) => b.id === targetId.value) || null)
const canInspect = computed(() =>
  !submitting.value
  && targetBatch.value
  && inspectForm.value.inspectedAt
  && inspectForm.value.conclusion
  && Number.isFinite(Number(inspectForm.value.brix))
  && Number.isFinite(Number(inspectForm.value.purity))
  && Number.isFinite(Number(inspectForm.value.fiber))
  && (inspectForm.value.conclusion === '通过' || inspectForm.value.rejectReason.trim()))

function emptyInspectForm() {
  return {
    baseVersion: 0,
    inspectedAt: nowLocal(),
    brix: 99.8,
    purity: 99.6,
    fiber: 0,
    conclusion: '通过',
    rejectReason: ''
  }
}

function newRequestId() {
  return window.crypto && crypto.randomUUID
    ? crypto.randomUUID()
    : String(Date.now()) + '-' + Math.random().toString(16).slice(2)
}

function pad(n) { return String(n).padStart(2, '0') }
function nowLocal() {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

async function load() {
  error.value = ''
  try {
    const p = {}
    const q = routeQuery.value.q
    if (q) p.warehouse = q
    if (filters.value.status) p.status = filters.value.status
    if (filters.value.inspectionStatus) p.inspectionStatus = filters.value.inspectionStatus
    const [rows, stockRows] = await Promise.all([
      api.listBatches(p), api.stockByWarehouse()
    ])
    batches.value = rows
    if (!rows.length && q && !filters.value.status && !filters.value.inspectionStatus) {
      batches.value = await api.listBatches()
    }
    stock.value = stockRows
  } catch (e) { error.value = e.message }
}

function flip(id) {
  const i = flipped.value.indexOf(id)
  if (i >= 0) flipped.value.splice(i, 1)
  else flipped.value.push(id)
}

async function create() {
  error.value = ''; tip.value = ''
  try {
    const b = await api.createBatch(form.value)
    tip.value = `批次 ${b.batchNo} 已建立，当前为待复检`
    form.value.batchNo = ''
    selectBatch(b.id, b)
    await load()
  } catch (e) { error.value = e.message }
}

function selectBatch(id, prefetched) {
  const b = prefetched || batches.value.find((x) => x.id === id)
  if (!b) return
  targetId.value = id
  const current = b.currentInspection
  inspectForm.value = {
    baseVersion: b.currentVersion || 0,
    inspectedAt: nowLocal(),
    brix: current ? current.brix : b.quality.brix,
    purity: current ? current.purity : b.quality.purity,
    fiber: current ? current.fiber : b.quality.fiber,
    conclusion: current && current.conclusion === '不通过' ? '不通过' : '通过',
    rejectReason: current && current.rejectReason ? current.rejectReason : ''
  }
  requestId.value = newRequestId()
}

async function submitInspection() {
  if (!targetBatch.value) return
  error.value = ''; tip.value = ''
  submitting.value = true
  try {
    const payload = {
      ...inspectForm.value,
      brix: Number(inspectForm.value.brix),
      purity: Number(inspectForm.value.purity),
      fiber: Number(inspectForm.value.fiber),
      rejectReason: inspectForm.value.conclusion === '通过' ? '' : inspectForm.value.rejectReason.trim(),
      inspectedAt: `${inspectForm.value.inspectedAt}:00`,
      requestId: requestId.value
    }
    const saved = await api.inspectBatch(targetBatch.value.id, payload)
    tip.value = payload.conclusion === '通过'
      ? `${saved.batchNo} V${saved.currentVersion} 复检通过，可办理入库`
      : `${saved.batchNo} V${saved.currentVersion} 复检不通过，原因已保留，发运已锁定`
    const savedId = targetBatch.value.id
    await load()
    const updated = batches.value.find((b) => b.id === savedId)
    if (updated) inspectForm.value.baseVersion = updated.currentVersion
    requestId.value = newRequestId()
  } catch (e) {
    error.value = /timeout|Network Error/.test(e.message)
      ? `${e.message}；如不确定结果可直接重试，同一请求幂等，不会重复保存`
      : e.message
  } finally {
    submitting.value = false
  }
}

async function store(id) {
  error.value = ''; tip.value = ''
  try {
    await api.storeBatch(id)
    tip.value = '已入库；发运资格跟随最新复检结论'
    await load()
  } catch (e) { error.value = e.message }
}

async function ship(id) {
  error.value = ''; tip.value = ''
  try {
    await api.shipBatch(id)
    tip.value = '已发运'
    await load()
  } catch (e) { error.value = e.message }
}

function fmt(t) {
  return t ? String(t).replace('T', ' ').slice(0, 16) : '—'
}

function eligibilityText(b) {
  if (b.status === '已发运') return '已发运'
  if (b.inspectionStatus === '待复检') return '待复检，入库锁定'
  if (b.inspectionStatus === '不通过') return `发运锁定：${b.rejectReason || '复检不通过'}`
  if (b.status === '待复检') return '复检通过，可入库'
  return b.shipmentEligible ? '可发运' : '发运锁定'
}

function stackClass(b) {
  return {
    flip: flipped.value.includes(b.id),
    wait: b.inspectionStatus !== '通过',
    pass: b.inspectionStatus === '通过'
  }
}

const maxKg = computed(() => {
  let m = 1
  for (const b of batches.value) m = Math.max(m, Number(b.quantityKg))
  return m
})

function stackH(b) {
  return Math.max(42, (Number(b.quantityKg) / maxKg.value) * 120) + 'px'
}

watch(() => routeQuery.value.q, () => load())
watch(filters, () => load(), { deep: true })
watch(inspectForm, () => { requestId.value = newRequestId() }, { deep: true })
onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">成品糖 · 糖仓堆叠</span>
        <span class="panel-desc">块高按吨位归一；点块翻面看投产指标，颜色随当前复检结论变化</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">批次</div><div class="v">{{ batches.length }}</div></div>
        <div class="stat"><div class="k">待复检</div><div class="v">{{ batches.filter(b => b.inspectionStatus === '待复检').length }}</div></div>
        <div class="stat"><div class="k">复检不通过</div><div class="v">{{ batches.filter(b => b.inspectionStatus === '不通过').length }}</div></div>
        <div class="stat"><div class="k">可发运</div><div class="v">{{ batches.filter(b => b.shipmentEligible).length }}</div></div>
        <div class="stat"><div class="k">已发运</div><div class="v">{{ batches.filter(b => b.status === '已发运').length }}</div></div>
      </div>

      <div class="filter-row">
        <label>批次状态
          <select v-model="filters.status" class="fld">
            <option value="">全部</option>
            <option v-for="s in BATCH_STATUSES" :key="s" :value="s">{{ s }}</option>
          </select>
        </label>
        <label>复检状态
          <select v-model="filters.inspectionStatus" class="fld">
            <option value="">全部</option>
            <option v-for="s in INSPECTION_STATUSES" :key="s" :value="s">{{ s }}</option>
          </select>
        </label>
      </div>

      <div class="wh-row">
        <div v-for="w in WAREHOUSES" :key="w" class="wh">
          <div class="wh-head">
            {{ w }}
            <span>{{ (byWh[w] || []).length }} 在仓批</span>
          </div>
          <div class="wh-body">
            <div
              v-for="b in (byWh[w] || [])"
              :key="b.id"
              class="stack"
              :class="stackClass(b)"
              :style="{ height: stackH(b) }"
              @click="flip(b.id)"
            >
              <div class="face front">
                <div class="s-no">{{ b.batchNo }}</div>
                <div class="s-grade">{{ b.grade }} · {{ b.inspectionStatus }}</div>
                <div class="s-q">{{ b.quantityTon }}t</div>
              </div>
              <div class="face back">
                <div class="b-t">投产指标 / 当前 V{{ b.currentVersion || 0 }}</div>
                <div class="b-i">糖度 {{ b.quality.brix }}°</div>
                <div class="b-i">纯度 {{ b.quality.purity }}%</div>
                <div class="b-i">纤维 {{ b.quality.fiber }}%</div>
                <div v-if="b.currentInspection" class="b-i">
                  复检 {{ b.currentInspection.brix }}° / {{ b.currentInspection.purity }}% / {{ b.currentInspection.fiber }}%
                </div>
              </div>
            </div>
            <div v-if="!(byWh[w] || []).length" class="wh-empty">空仓</div>
          </div>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">糖仓汇总</span>
        <span class="panel-desc">按最新复检版本区分待复检、可发运和已发运数量</span>
      </div>
      <div class="stock-grid">
        <div v-for="row in stock" :key="row.warehouse" class="stock-card">
          <div class="stock-name">{{ row.warehouse }}</div>
          <div class="stock-line pending">
            <span>待复检</span><strong>{{ row.pendingInspectionTon }}t</strong>
            <em>{{ row.pendingInspectionBatches }}批</em>
          </div>
          <div v-if="row.awaitingStoreBatches" class="stock-line waiting">
            <span>通过待入库</span><strong>{{ row.awaitingStoreTon }}t</strong>
            <em>{{ row.awaitingStoreBatches }}批</em>
          </div>
          <div class="stock-line ready">
            <span>可发运</span><strong>{{ row.shippableTon }}t</strong>
            <em>{{ row.shippableBatches }}批</em>
          </div>
          <div class="stock-line shipped">
            <span>已发运</span><strong>{{ row.shippedTon }}t</strong>
            <em>{{ row.shippedBatches }}批</em>
          </div>
          <div v-if="row.lockedBatches" class="stock-lock">
            异常锁定 {{ row.lockedTon }}t / {{ row.lockedBatches }}批
          </div>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">质量复检 · 发运锁定</span>
        <span class="panel-desc">复检按批号和版本追加；已入库或已发运后重检生成新版本，历史不覆盖</span>
      </div>
      <div class="inspect-box">
        <div class="inspect-target">
          <template v-if="targetBatch">
            <div class="it-no">{{ targetBatch.batchNo }} <span>V{{ targetBatch.currentVersion || 0 }}</span></div>
            <div class="it-meta">{{ targetBatch.grade }} · {{ targetBatch.quantityKg }}kg · {{ targetBatch.status }}</div>
            <div class="it-meta">最近复检：{{ fmt(targetBatch.lastInspectedAt) }}</div>
          </template>
          <div v-else class="it-empty">请在下方批次表点击「复检」</div>
        </div>
        <div class="inspect-form">
          <input v-model="inspectForm.inspectedAt" type="datetime-local" class="fld" />
          <input v-model.number="inspectForm.brix" class="fld n" placeholder="复检糖度" />
          <input v-model.number="inspectForm.purity" class="fld n" placeholder="复检纯度" />
          <input v-model.number="inspectForm.fiber" class="fld n" placeholder="纤维分" />
          <select v-model="inspectForm.conclusion" class="fld">
            <option value="通过">通过</option>
            <option value="不通过">不通过</option>
          </select>
          <input
            v-model="inspectForm.rejectReason"
            class="fld reason"
            :disabled="inspectForm.conclusion === '通过'"
            placeholder="不通过原因（必填）"
          />
          <button class="act primary" :disabled="!canInspect" @click="submitInspection">
            {{ submitting ? '提交中…' : '提交复检 V' + ((targetBatch?.currentVersion || 0) + 1) }}
          </button>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">批次流转</span>
        <span class="panel-desc">待复检 → 已入库 → 已发运；发运按钮只跟随最新复检结论启用</span>
      </div>
      <div class="bl">
        <div v-for="b in batches" :key="b.id" class="bl-row" :class="{ selected: b.id === targetId }">
          <span class="bl-no">{{ b.batchNo }}</span>
          <span class="bl-g">{{ b.grade }}</span>
          <span class="bl-q">{{ b.quantityKg }}kg</span>
          <span class="bl-version">V{{ b.currentVersion || 0 }}</span>
          <span class="bl-p" :class="{ fail: b.inspectionStatus === '不通过', pass: b.inspectionStatus === '通过' }">
            {{ b.inspectionStatus }} · {{ fmt(b.lastInspectedAt) }}
          </span>
          <span class="bl-ship" :class="{ on: b.shipmentEligible, off: !b.shipmentEligible && b.status !== '已发运' }">
            {{ eligibilityText(b) }}
          </span>
          <span class="bl-st">{{ b.status }}</span>
          <button class="act sm" @click="selectBatch(b.id, b)">复检</button>
          <button class="act sm" :disabled="!b.storeEligible" @click="store(b.id)">入库</button>
          <button class="act sm primary" :disabled="!b.shipmentEligible" @click="ship(b.id)">发运</button>
        </div>
        <div v-if="!batches.length" class="empty">暂无符合筛选条件的批次</div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">新建批次</span>
        <span class="panel-desc">新建后先待复检；纯度不足 99.5% 不能判白砂糖；单批上限 60000kg</span>
      </div>
      <div class="form-row">
        <input v-model="form.batchNo" class="fld" placeholder="批号" />
        <input v-model.number="form.quantityKg" class="fld n" placeholder="数量kg" />
        <select v-model="form.warehouse" class="fld">
          <option v-for="w in WAREHOUSES" :key="w" :value="w">{{ w }}</option>
        </select>
        <input v-model.number="form.quality.brix" class="fld n" placeholder="糖度" />
        <input v-model.number="form.quality.purity" class="fld n" placeholder="纯度" />
        <input v-model.number="form.quality.fiber" class="fld n" placeholder="纤维分" />
        <button class="act primary" @click="create">建立批次</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.filter-row { display: flex; gap: 12px; margin: 10px 0; }
.filter-row label { font-size: 12px; color: var(--muted); display: flex; align-items: center; gap: 6px; }

.wh-row { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.wh { border: 1px solid var(--line); border-radius: 9px; overflow: hidden; }
.wh-head {
  background: var(--primary-wash); color: var(--primary-deep);
  font-weight: 800; font-size: 13px; padding: 7px 10px;
  display: flex; justify-content: space-between;
}
.wh-head span { font-weight: 600; font-size: 12px; color: var(--primary-soft); }

.wh-body {
  display: flex; flex-direction: column-reverse; gap: 5px;
  padding: 10px; min-height: 150px; justify-content: flex-start; background: #fff;
}
.wh-empty { font-size: 12px; color: var(--muted); align-self: center; }

.stack {
  position: relative; cursor: pointer;
  transform-style: preserve-3d; transition: transform 0.45s;
  border-radius: 6px;
}
.stack.flip { transform: rotateY(180deg); }

.face {
  position: absolute; inset: 0; backface-visibility: hidden;
  border-radius: 6px; border: 1px solid #d9dfae;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding: 4px;
}
.face.front { background: linear-gradient(180deg, #e6eeb8, var(--primary-soft)); }
.stack.wait .face.front { background: linear-gradient(180deg, #fff9c4, #ffd54f); }
.stack.pass .face.front { border-color: #81c784; }
.face.back { background: var(--primary-deep); color: #fff; transform: rotateY(180deg); }

.s-no { font-size: 10px; font-weight: 800; color: #4a5008; }
.s-grade { font-size: 11px; font-weight: 700; }
.s-q { font-size: 10px; opacity: 0.85; }
.b-t { font-size: 9px; opacity: 0.8; }
.b-i { font-size: 10px; }

.stock-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.stock-card { border: 1px solid var(--line); border-radius: 9px; padding: 10px; background: #fff; }
.stock-name { font-weight: 800; color: var(--primary-deep); margin-bottom: 8px; }
.stock-line { display: grid; grid-template-columns: 1fr auto auto; gap: 8px; font-size: 12px; padding: 4px 0; }
.stock-line strong { font-size: 14px; }
.stock-line em { color: var(--muted); font-style: normal; width: 42px; text-align: right; }
.stock-line.pending { color: #f57f17; }
.stock-line.waiting { color: #ef6c00; }
.stock-line.ready { color: #2e7d32; }
.stock-line.shipped { color: #1565c0; }
.stock-lock { margin-top: 6px; font-size: 11px; color: #c62828; }

.inspect-box { display: grid; grid-template-columns: 230px 1fr; gap: 12px; }
.inspect-target { border: 1px dashed var(--primary-soft); border-radius: 9px; padding: 10px; background: var(--primary-wash); }
.it-no { font-size: 16px; font-weight: 900; color: var(--primary-deep); }
.it-no span { font-size: 12px; margin-left: 6px; }
.it-meta, .it-empty { font-size: 12px; color: var(--muted); margin-top: 5px; }
.inspect-form { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.inspect-form .fld { min-width: 130px; }
.inspect-form .reason { flex: 1 1 220px; }

.bl { display: flex; flex-direction: column; gap: 5px; }
.bl-row { display: flex; align-items: center; gap: 8px; padding: 6px 10px; border: 1px solid var(--line); border-radius: 8px; font-size: 12px; }
.bl-row.selected { border-color: var(--primary); box-shadow: 0 0 0 2px var(--primary-wash); }
.bl-no { font-weight: 800; color: var(--primary-deep); width: 108px; }
.bl-g, .bl-q, .bl-version, .bl-p, .bl-st, .bl-ship { color: var(--muted); }
.bl-g { width: 58px; }
.bl-q { width: 82px; }
.bl-version { width: 38px; font-weight: 700; }
.bl-p { width: 178px; }
.bl-p.pass { color: #2e7d32; font-weight: 700; }
.bl-p.fail { color: #c62828; font-weight: 700; }
.bl-ship { flex: 1; min-width: 180px; }
.bl-ship.on { color: #2e7d32; font-weight: 800; }
.bl-ship.off { color: #c62828; }
.bl-st { width: 62px; }

.form-row { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.empty { font-size: 12px; color: var(--muted); padding: 8px 2px; }

@media (max-width: 1000px) {
  .wh-row, .stock-grid { grid-template-columns: 1fr; }
  .inspect-box { grid-template-columns: 1fr; }
  .bl-row { flex-wrap: wrap; }
}
</style>
