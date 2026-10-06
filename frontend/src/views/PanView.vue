<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '../api'

// ------------------------------------------------------------------
// 页面操作范式：蒸发结晶罐洗罐排程闭环。
// 顶部仍是罐体圆柱阵列（按容积归一、超 72h 描红）；下方两张主表：
//   ① 待洗清单——按罐型 / 当前状态 / 上次洗罐时间查看，逐行对齐罐的运行资格、
//      洗罐窗口与未完成排程，超 72h 的罐标「需先洗罐」，停用罐不能安排洗罐；
//   ② 洗罐安排看板——待执行 → 清洗中 → 已完成 推进，待执行可取消，
//      清洗中不能重复开始、不能改罐型，完成时同事务写完成时间并恢复运行资格。
// 失败原因落在对应安排上（不是单独一条列表提示），刷新后仍可据此判断能否重试。
// ------------------------------------------------------------------

const pans = ref([])       // 罐体（阵列 + 计数）
const queue = ref([])      // 待洗清单（罐与未完成排程对齐）
const washes = ref([])     // 洗罐安排看板
const error = ref('')
const tip = ref('')
const busy = ref(false)

// 筛选
const filterType = ref('')
const filterStatus = ref('')
const dueOnly = ref(false)

// 安排洗罐窗口表单
const formPanId = ref(null)
const formWindow = ref(defaultWindow())
const scheduleFilter = ref('')

function defaultWindow() {
  const d = new Date(Date.now() + 60 * 60 * 1000)
  d.setMinutes(0, 0, 0)
  return toLocalInput(d)
}

// datetime-local 需要本地时区的 yyyy-MM-ddTHH:mm
function toLocalInput(d) {
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}

// 后端 LocalDateTime 序列化为无时区 ISO（yyyy-MM-ddTHH:mm:ss），按本地时间解析展示
function fmt(t) {
  if (!t) return '—'
  const s = String(t).replace('T', ' ')
  return s.length >= 16 ? s.slice(0, 16) : s
}

function hoursText(h) {
  return h === null || h === undefined ? '未洗过' : `${h} 小时`
}

const visiblePans = computed(() =>
  filterType.value ? pans.value.filter((p) => p.panType === filterType.value) : pans.value
)

const dueCount = computed(() => pans.value.filter((p) => p.isWashDue).length)
const activeCount = computed(() => washes.value.filter((w) => w.status === '待执行' || w.status === '清洗中').length)

const visibleWashes = computed(() =>
  scheduleFilter.value ? washes.value.filter((w) => w.status === scheduleFilter.value) : washes.value
)

const maxCap = computed(() => {
  let m = 1
  for (const p of pans.value) m = Math.max(m, Number(p.capacityM3))
  return m
})

function tankH(p) {
  return Math.max(48, (Number(p.capacityM3) / maxCap.value) * 130) + 'px'
}

async function load() {
  error.value = ''
  try {
    const [p, q, w] = await Promise.all([
      api.listPans(),
      api.washQueue({
        panType: filterType.value || undefined,
        panStatus: filterStatus.value || undefined,
        dueOnly: dueOnly.value || undefined
      }),
      api.listWashes()
    ])
    pans.value = p
    queue.value = q
    washes.value = w
    // 选中的罐若已不在清单（被筛掉）则松开
    if (formPanId.value && !queue.value.some((r) => r.panId === formPanId.value)) {
      formPanId.value = null
    }
  } catch (e) { error.value = e.message }
}

// 可安排洗罐窗口的罐：非停用、当前无未完成安排
function canSchedule(r) {
  return r.status !== '停用' && !r.activeWash
}

async function submitSchedule(panIdArg) {
  const panId = panIdArg ?? formPanId.value
  if (!panId) { error.value = '请选择要安排洗罐的罐'; return }
  if (!formWindow.value) { error.value = '请选择洗罐窗口'; return }
  error.value = ''; tip.value = ''
  busy.value = true
  try {
    const w = await api.scheduleWash(panId, formWindow.value)
    tip.value = `已为 ${w.panName} 安排洗罐窗口（${w.washNo}）：${fmt(w.windowAt)}`
    formPanId.value = null
    formWindow.value = defaultWindow()
    await load()
  } catch (e) { error.value = e.message } finally { busy.value = false }
}

async function startWash(w) {
  error.value = ''; tip.value = ''
  busy.value = true
  try {
    await api.startWash(w.id)
    tip.value = `${w.washNo} 已开始清洗（罐已转为「洗罐」），此后不能重复开始、不能改换罐型`
    await load()
  } catch (e) { error.value = e.message; await load() } finally { busy.value = false }
}

async function cancelWash(w) {
  error.value = ''; tip.value = ''
  busy.value = true
  try {
    await api.cancelWash(w.id)
    tip.value = `${w.washNo} 已取消，该罐可重新安排洗罐`
    await load()
  } catch (e) { error.value = e.message } finally { busy.value = false }
}

async function completeWash(w) {
  error.value = ''; tip.value = ''
  busy.value = true
  try {
    await api.completeWash(w.id)
    tip.value = `${w.washNo} 洗罐完成：已写入完成时间与上次洗罐时间，${w.panName} 恢复运行资格`
    await load()
  } catch (e) { error.value = e.message; await load() } finally { busy.value = false }
}

async function executeWash(w) {
  error.value = ''; tip.value = ''
  busy.value = true
  try {
    await api.executeWash(w.id)
    tip.value = `${w.washNo} 已一键清洗完成：完成时间与上次洗罐时间同事务写入，${w.panName} 恢复运行资格`
    await load()
  } catch (e) { error.value = e.message; await load() } finally { busy.value = false }
}

// 勾选多笔待执行/清洗中安排一键清洗；逐笔独立事务，某只失败不影响已完成的罐
const checkedWash = ref([])
function toggleWash(id) {
  const i = checkedWash.value.indexOf(id)
  if (i >= 0) checkedWash.value.splice(i, 1)
  else checkedWash.value.push(id)
}
function checkable(w) {
  return w.status === '待执行' || w.status === '清洗中'
}

async function executeBatch() {
  if (!checkedWash.value.length) return
  error.value = ''; tip.value = ''
  busy.value = true
  const targets = [...checkedWash.value]
  let ok = 0
  const fails = []
  for (const id of targets) {
    try { await api.executeWash(id); ok++ } catch (e) { fails.push(e.message) }
  }
  checkedWash.value = []
  await load()
  busy.value = false
  if (fails.length) {
    // 已完成的保留；失败罐仍停在可安全重试状态，原因已落在对应安排上
    error.value = `一键清洗：成功 ${ok} 只，失败 ${fails.length} 只——失败罐仍可在看板上重试（${fails[0]}）`
  } else {
    tip.value = `一键清洗完成：成功 ${ok} 只，完成时间与运行资格已同步`
  }
}

function countWash(s) {
  return washes.value.filter((w) => w.status === s).length
}

onMounted(load)
</script>

<template>
  <div>
    <!-- ============ 罐体阵列（概览） ============ -->
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">蒸发 / 结晶罐 · 罐体阵列</span>
        <span class="panel-desc">罐高按容积归一；超 72 小时未洗的罐整只描红；停用罐灰显</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">罐体</div><div class="v">{{ pans.length }}</div></div>
        <div class="stat"><div class="k">有运行资格</div><div class="v">{{ pans.filter(p => p.operable !== false).length }}</div></div>
        <div class="stat"><div class="k">需先洗罐</div><div class="v">{{ dueCount }}</div></div>
        <div class="stat"><div class="k">未完成安排</div><div class="v">{{ activeCount }}</div></div>
      </div>

      <div class="tool-row">
        <select v-model="filterType" class="fld" @change="load">
          <option value="">全部罐型</option>
          <option>蒸发罐</option>
          <option>结晶罐</option>
        </select>
        <span class="spacer"></span>
      </div>

      <div class="pan-row">
        <div
          v-for="p in visiblePans" :key="p.id" class="tank"
          :class="{ due: p.isWashDue, stopped: p.status === '停用' }"
        >
          <div class="t-cap" :class="'s-' + p.status"></div>
          <div class="t-body" :style="{ height: tankH(p) }">
            <div class="t-fill" :class="'s-' + p.status"></div>
            <span class="t-code">{{ p.panCode }}</span>
          </div>
          <div class="t-base"></div>
          <div class="t-name">{{ p.panName }}</div>
          <div class="t-type">{{ p.panType }} · {{ p.capacityM3 }}m³</div>
          <div class="t-wash" :class="{ warn: p.isWashDue }">
            {{ p.hoursSinceWash === null ? '未洗过' : p.hoursSinceWash + 'h 前洗' }}
          </div>
        </div>
        <div v-if="!visiblePans.length" class="empty">没有符合条件的罐体</div>
      </div>
    </div>

    <!-- ============ 待洗清单 ============ -->
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">洗罐待洗清单</span>
        <span class="panel-desc">按罐型 / 当前状态 / 距上次洗罐时长查看；超 72 小时标「需先洗罐」，停用罐不能安排洗罐</span>
      </div>

      <div class="tool-row">
        <select v-model="filterType" class="fld" @change="load">
          <option value="">全部罐型</option>
          <option>蒸发罐</option>
          <option>结晶罐</option>
        </select>
        <select v-model="filterStatus" class="fld" @change="load">
          <option value="">全部状态</option>
          <option>运行</option>
          <option>洗罐</option>
          <option>停用</option>
        </select>
        <label class="due-chk">
          <input type="checkbox" v-model="dueOnly" @change="load" /> 只看需先洗罐（&gt;72h）
        </label>
        <span class="spacer"></span>
      </div>

      <!-- 安排洗罐窗口 -->
      <div class="deal-bar">
        <span class="deal-label">安排洗罐窗口</span>
        <select v-model="formPanId" class="fld" style="min-width:200px">
          <option :value="null" disabled>选择罐…</option>
          <option v-for="r in queue.filter(canSchedule)" :key="r.panId" :value="r.panId">
            {{ r.panCode }} {{ r.panName }}（{{ r.panType }} · {{ r.status }}{{ r.washDue ? ' · 已到期' : '' }}）
          </option>
        </select>
        <input v-model="formWindow" type="datetime-local" class="fld" style="width:200px" />
        <button class="act primary" :disabled="busy || !formPanId || !formWindow" @click="submitSchedule()">
          {{ busy ? '提交中…' : '安排洗罐' }}
        </button>
      </div>

      <table class="grid">
        <thead>
          <tr>
            <th>罐号 / 罐名</th><th>罐型</th><th>当前状态</th><th>距上次洗罐</th>
            <th>洗罐判定</th><th>洗罐窗口 / 排程</th><th>运行资格</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in queue" :key="r.panId" :class="{ 'row-due': r.washDue }">
            <td><span class="cell-code">{{ r.panCode }}</span> {{ r.panName }}</td>
            <td>{{ r.panType }}</td>
            <td><span class="tag" :class="'tag-st-' + r.status">{{ r.status }}</span></td>
            <td :class="{ warn: r.washDue }">{{ hoursText(r.hoursSinceWash) }}</td>
            <td>
              <span v-if="r.washDue" class="badge badge-due">需先洗罐</span>
              <span v-else class="muted">未到期</span>
            </td>
            <td>
              <template v-if="r.activeWash">
                <div class="aw">
                  <span class="tag" :class="'tag-ws-' + r.activeWash.washStatus">{{ r.activeWash.washStatus }}</span>
                  <span>窗口 {{ fmt(r.activeWash.windowAt) }}</span>
                </div>
                <div v-if="r.activeWash.failedReason" class="fail-inline" :class="{ retry: r.activeWash.retryable }">
                  {{ r.activeWash.retryable ? '可重试：' : '' }}{{ r.activeWash.failedReason }}
                </div>
              </template>
              <span v-else-if="r.status === '停用'" class="muted">停用中，不能安排</span>
              <span v-else class="muted">未安排</span>
            </td>
            <td>
              <span v-if="r.operable" class="badge badge-ok">有资格</span>
              <span v-else class="badge badge-no" :title="r.inoperableReason">无资格</span>
              <div v-if="!r.operable" class="reason">{{ r.inoperableReason }}</div>
            </td>
            <td>
              <button v-if="canSchedule(r)" class="act sm" @click="formPanId = r.panId; formWindow = defaultWindow()">
                安排
              </button>
              <span v-else-if="r.activeWash" class="muted">看下方看板</span>
              <span v-else class="muted">—</span>
            </td>
          </tr>
          <tr v-if="!queue.length"><td colspan="8" class="empty" style="text-align:center">没有符合条件的罐</td></tr>
        </tbody>
      </table>
    </div>

    <!-- ============ 洗罐安排看板 ============ -->
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">洗罐安排看板</span>
        <span class="panel-desc">待执行 → 清洗中 → 已完成；待执行可取消，清洗中不能重复开始 / 改换罐型，完成时恢复运行资格</span>
      </div>

      <div class="stat-row">
        <div class="stat"><div class="k">待执行</div><div class="v">{{ countWash('待执行') }}</div></div>
        <div class="stat"><div class="k">清洗中</div><div class="v">{{ countWash('清洗中') }}</div></div>
        <div class="stat"><div class="k">已完成</div><div class="v">{{ countWash('已完成') }}</div></div>
        <div class="stat"><div class="k">已取消</div><div class="v">{{ countWash('已取消') }}</div></div>
      </div>

      <div class="tool-row">
        <select v-model="scheduleFilter" class="fld">
          <option value="">全部安排</option>
          <option>待执行</option>
          <option>清洗中</option>
          <option>已完成</option>
          <option>已取消</option>
        </select>
        <span class="spacer"></span>
        <span class="picked">已勾 {{ checkedWash.length }} 笔</span>
        <button class="act" :disabled="!checkedWash.length" @click="checkedWash = []">清空</button>
        <button class="act primary" :disabled="busy || !checkedWash.length" @click="executeBatch">
          {{ busy ? '执行中…' : '一键清洗勾选罐' }}
        </button>
      </div>

      <table class="grid">
        <thead>
          <tr>
            <th style="width:32px"></th><th>排程单号</th><th>罐号 / 罐名</th><th>罐型</th>
            <th>洗罐窗口</th><th>状态</th><th>开始 / 完成时间</th><th>失败与重试</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="w in visibleWashes" :key="w.id" :class="{ 'row-failed': w.failedReason && w.retryable }">
            <td>
              <input v-if="checkable(w)" type="checkbox" :checked="checkedWash.includes(w.id)" @change="toggleWash(w.id)" />
            </td>
            <td><span class="cell-code">{{ w.washNo }}</span></td>
            <td>{{ w.panCode }} {{ w.panName }}</td>
            <td>{{ w.panType }}</td>
            <td>{{ fmt(w.windowAt) }}</td>
            <td><span class="tag" :class="'tag-ws-' + w.status">{{ w.status }}</span></td>
            <td class="time-cell">
              <div>开始：{{ fmt(w.startedAt) }}</div>
              <div>完成：{{ fmt(w.completedAt) }}</div>
            </td>
            <td>
              <div v-if="w.failedReason" class="fail-inline" :class="{ retry: w.retryable }">
                <span v-if="w.retryable" class="retry-flag">失败·可重试</span>
                <span v-else>失败（已终态）</span>
                {{ w.failedReason }}
              </div>
              <span v-else class="muted">—</span>
            </td>
            <td class="ops">
              <template v-if="w.status === '待执行'">
                <button class="act sm primary" :disabled="busy" @click="startWash(w)">开始清洗</button>
                <button class="act sm" :disabled="busy" @click="executeWash(w)">一键清洗</button>
                <button class="act sm" :disabled="busy" @click="cancelWash(w)">取消</button>
              </template>
              <template v-else-if="w.status === '清洗中'">
                <button class="act sm primary" :disabled="busy" @click="completeWash(w)">完成洗罐</button>
                <button class="act sm" :disabled="busy" @click="executeWash(w)">重试完成</button>
              </template>
              <template v-else>
                <span class="muted">{{ w.status === '已完成' ? '完成时间 ' + fmt(w.completedAt) : '已于 ' + fmt(w.cancelledAt) + ' 取消' }}</span>
              </template>
            </td>
          </tr>
          <tr v-if="!visibleWashes.length"><td colspan="9" class="empty" style="text-align:center">暂无洗罐安排</td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.tool-row { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.spacer { flex: 1; }
.picked { font-size: 12px; color: var(--primary-deep); font-weight: 700; }
.due-chk { font-size: 12px; color: var(--primary-deep); display: flex; align-items: center; gap: 4px; }

/* 罐体阵列 */
.pan-row { display: flex; gap: 16px; flex-wrap: wrap; align-items: flex-end; }
.tank {
  position: relative; width: 104px; text-align: center;
  padding: 8px 6px; border: 1px solid var(--line); border-radius: 10px; background: #fff;
}
.tank.due { border-color: #ef9a9a; box-shadow: 0 0 0 2px #ffebee; }
.tank.stopped { opacity: 0.7; }
.t-cap { height: 10px; border-radius: 5px 5px 0 0; background: var(--primary); margin: 0 8px; }
.t-cap.s-洗罐 { background: #ffb300; }
.t-cap.s-停用 { background: #bdbdbd; }
.t-body {
  margin: 0 8px; border-radius: 3px; background: #eef1e2;
  border: 1px solid var(--line); position: relative; overflow: hidden;
  display: flex; align-items: center; justify-content: center;
}
.t-fill { position: absolute; bottom: 0; left: 0; right: 0; height: 62%; }
.t-fill.s-运行 { background: linear-gradient(180deg, #d4e157, var(--primary)); }
.t-fill.s-洗罐 { background: repeating-linear-gradient(45deg, #ffe082, #ffe082 6px, #ffd54f 6px, #ffd54f 12px); }
.t-fill.s-停用 { background: #e0e0e0; }
.t-code { position: relative; font-size: 11px; font-weight: 800; color: var(--primary-deep); }
.t-base { height: 6px; margin: 0 4px; border-radius: 0 0 6px 6px; background: var(--primary-deep); }
.t-name { font-size: 12px; font-weight: 700; color: var(--primary-deep); margin-top: 5px; }
.t-type { font-size: 10px; color: var(--muted); }
.t-wash { font-size: 10px; color: var(--muted); margin-top: 3px; }
.t-wash.warn { color: #c62828; font-weight: 800; }

/* 安排窗口条 */
.deal-bar {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  border: 1px dashed var(--primary-soft); border-radius: 9px;
  background: var(--primary-wash); padding: 10px 12px; margin-bottom: 12px;
}
.deal-label { font-size: 13px; font-weight: 800; color: var(--primary-deep); }

/* 表格 */
.grid { width: 100%; border-collapse: collapse; font-size: 12.5px; }
.grid th {
  text-align: left; padding: 8px 9px; background: var(--primary-wash);
  color: var(--primary-deep); font-weight: 800; border-bottom: 1px solid var(--line);
  white-space: nowrap;
}
.grid td { padding: 8px 9px; border-bottom: 1px solid var(--line); vertical-align: top; }
.grid tr.row-due { background: #fffdf5; }
.grid tr.row-failed { background: #fff8f6; }
.cell-code { font-weight: 800; color: var(--primary-deep); }
.muted { color: var(--muted); font-size: 12px; }
.warn { color: #c62828; font-weight: 800; }
.reason { font-size: 11px; color: var(--muted); margin-top: 3px; max-width: 190px; }
.time-cell { color: var(--muted); font-size: 12px; line-height: 1.7; white-space: nowrap; }
.aw { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.ops { white-space: nowrap; }
.ops .act { margin-right: 4px; }
.empty { font-size: 13px; color: var(--muted); padding: 10px; }

/* 状态标签 */
.tag { font-size: 11px; font-weight: 800; padding: 1px 8px; border-radius: 9px; white-space: nowrap; }
.tag-st-运行 { background: #e8f5e9; color: #2e7d32; }
.tag-st-洗罐 { background: #fff8e1; color: #f57f17; }
.tag-st-停用 { background: #eceff1; color: #607d8b; }
.tag-ws-待执行 { background: #fff8e1; color: #f57f17; }
.tag-ws-清洗中 { background: #e3f2fd; color: #1565c0; }
.tag-ws-已完成 { background: #e8f5e9; color: #2e7d32; }
.tag-ws-已取消 { background: #eceff1; color: #607d8b; }

.badge { font-size: 11px; font-weight: 800; padding: 2px 9px; border-radius: 9px; white-space: nowrap; }
.badge-due { background: #ffebee; color: #c62828; border: 1px solid #ef9a9a; }
.badge-ok { background: #e8f5e9; color: #2e7d32; }
.badge-no { background: #eceff1; color: #607d8b; }

/* 失败留痕（长在对应安排/罐上，刷新后仍在） */
.fail-inline {
  margin-top: 4px; font-size: 11px; color: #a32b20;
  background: #fdecea; border: 1px solid #f5c6c2; border-radius: 6px;
  padding: 3px 7px; max-width: 260px;
}
.fail-inline.retry { border-color: #ef9a9a; }
.retry-flag { display: inline-block; font-weight: 800; margin-right: 4px; }
</style>
