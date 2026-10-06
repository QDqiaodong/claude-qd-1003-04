<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import router from '../router'
import api from '../api'
import {
  INTAKE_STATUSES,
  INTAKE_RESULTS,
  buildIntakeParams,
  intakeFiltersFromQuery,
  createLoadGate,
  intakeLoadErrorMessage
} from '../utils/intakeQuery'

// ------------------------------------------------------------------
// 页面操作范式：过磅三联柱 + 品质胶囊。
// 每张磅单画成一根竖直的三段柱：底段毛重、其上皮重（挖掉的一段）、顶端扣杂（斜纹），
// 剩下那截高亮的就是净重——重量关系一眼看得出来，不用读数字。
// 右侧三枚胶囊直接给出糖度/纯度/纤维分，结论徽章跟着 record 自动判定走。
//
// 筛选口径：验收状态与品质结论可单独或组合使用，组合按「同时满足」过滤；
// 条件与地址查询参数（?status=…&result=…）双向对齐，直接打开 / 刷新还原同一批结果；
// 合法空结果保持空态，不回退为全部磅单；连续快速切换时只认最后一次选择的响应。
// ------------------------------------------------------------------

const routeQuery = computed(() => router.currentRoute.value.query || {})
const intakes = ref([])
const error = ref('')
const tip = ref('')
const selectedId = ref(null)
const form = ref({
  ticketNo: '', supplier: '', truckNo: '',
  grossKg: 30000, tareKg: 11800, deductionKg: 600,
  quality: { brix: 13.5, purity: 86.0, fiber: 12.5 }
})
const daily = ref(0)

// 筛选条件：初始值取自地址，之后由下拉框与地址监听双向同步
const filters = ref(intakeFiltersFromQuery(routeQuery.value))

// 加载序列闸：连续快速切换条件时，较早发出的慢响应不得覆盖最后一次选择
const loadGate = createLoadGate()

const selected = computed(() => intakes.value.find((i) => i.id === selectedId.value) || null)

// 预览：净重 = 毛重 - 皮重 - 扣杂
const previewNet = computed(() => {
  const g = Number(form.value.grossKg) || 0
  const t = Number(form.value.tareKg) || 0
  const d = Number(form.value.deductionKg) || 0
  return g - t - d
})

const previewRate = computed(() => {
  const base = (Number(form.value.grossKg) || 0) - (Number(form.value.tareKg) || 0)
  return base > 0 ? ((Number(form.value.deductionKg) || 0) / base * 100).toFixed(1) : '0.0'
})

async function load() {
  const token = loadGate.next()
  error.value = ''
  // 列表按当前筛选条件查询；今日进厂总量是独立口径（全厂当日有效磅单），不随列表条件缩小
  const [listRes, tonRes] = await Promise.allSettled([
    api.listIntakes(buildIntakeParams(filters.value)),
    api.dailyTon()
  ])
  if (!loadGate.isCurrent(token)) return // 已有更新的条件，这份迟到的响应直接丢弃
  if (listRes.status === 'fulfilled') {
    intakes.value = listRes.value
    if (!listRes.value.some((i) => i.id === selectedId.value)) {
      selectedId.value = listRes.value.length ? listRes.value[0].id : null
    }
  }
  if (tonRes.status === 'fulfilled') {
    daily.value = tonRes.value
  }
  const failed = [listRes, tonRes].find((r) => r.status === 'rejected')
  if (failed) {
    // 失败不改筛选条件、不动地址；提示后值班员按原条件重试即可
    error.value = intakeLoadErrorMessage(failed.reason)
  }
}

async function create() {
  error.value = ''; tip.value = ''
  try {
    await api.createIntake(form.value)
    tip.value = '磅单已登记，净重 ' + previewNet.value + ' 千克'
    form.value.ticketNo = ''
    await load()
  } catch (e) { error.value = e.message }
}

async function accept(id) {
  error.value = ''; tip.value = ''
  try {
    await api.acceptIntake(id)
    tip.value = '验收完成'
    await load()
  } catch (e) { error.value = e.message }
}

async function reject(id) {
  error.value = ''; tip.value = ''
  try {
    await api.rejectIntake(id, '品质不达标')
    tip.value = '已拒收'
    await load()
  } catch (e) { error.value = e.message }
}

// 三段柱：按毛重为基准归一
function seg(v, gross) {
  const g = Number(gross) || 1
  return Math.max(0, Math.min(100, (Number(v) / g) * 100)) + '%'
}

// 下拉框变化 → 写回地址（清空某一项只移除对应参数），由地址监听统一触发加载
watch(filters, () => {
  const query = buildIntakeParams(filters.value)
  const cur = routeQuery.value
  if ((query.status || '') === (cur.status || '') && (query.result || '') === (cur.result || '')) return
  router.replace({ path: router.currentRoute.value.path, query })
}, { deep: true })

// 地址变化（直接打开 / 刷新 / 前进后退 / 筛选写回）→ 对齐下拉框并按地址条件加载
watch(routeQuery, (q) => {
  const next = intakeFiltersFromQuery(q)
  if (next.status !== filters.value.status || next.result !== filters.value.result) {
    filters.value = next
  }
  load()
})

onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">甘蔗进厂 · 过磅三联柱</span>
        <span class="panel-desc">柱高按毛重归一；挖掉皮重与扣杂，剩下高亮那段就是净重</span>
      </div>

      <div v-if="error" class="err">
        <span>{{ error }}</span>
        <button class="act sm retry" @click="load">重试</button>
      </div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">磅单</div><div class="v">{{ intakes.length }}</div></div>
        <div class="stat"><div class="k">已验收</div><div class="v">{{ intakes.filter(i => i.status === '已验收').length }}</div></div>
        <div class="stat"><div class="k">拒收</div><div class="v">{{ intakes.filter(i => i.result === '拒收').length }}</div></div>
        <div class="stat"><div class="k">今日进厂(吨)</div><div class="v">{{ daily }}</div></div>
      </div>

      <div class="filter-row">
        <label>验收状态
          <select v-model="filters.status" class="fld">
            <option value="">全部</option>
            <option v-for="s in INTAKE_STATUSES" :key="s" :value="s">{{ s }}</option>
          </select>
        </label>
        <label>品质结论
          <select v-model="filters.result" class="fld">
            <option value="">全部</option>
            <option v-for="r in INTAKE_RESULTS" :key="r" :value="r">{{ r }}</option>
          </select>
        </label>
      </div>

      <div class="scale-row">
        <div v-for="i in intakes" :key="i.id" class="scale" :class="{ on: i.id === selectedId }" @click="selectedId = i.id">
          <div class="col">
            <div class="seg ded" :style="{ height: seg(i.deductionKg, i.grossKg) }"></div>
            <div class="seg tare" :style="{ height: seg(i.tareKg, i.grossKg) }"></div>
            <div class="seg net" :style="{ height: seg(i.netKg, i.grossKg) }"></div>
          </div>
          <div class="s-no">{{ i.ticketNo }}</div>
          <div class="s-net">{{ i.netKg }}kg</div>
          <div class="s-res" :class="'r-' + i.result">{{ i.result }}</div>
        </div>
        <div v-if="!intakes.length" class="empty">暂无符合筛选条件的磅单</div>
      </div>

      <div class="legend">
        <span class="lg"><i class="sw net"></i>净重</span>
        <span class="lg"><i class="sw tare"></i>皮重</span>
        <span class="lg"><i class="sw ded"></i>扣杂</span>
      </div>
    </div>

    <div class="panel" v-if="selected">
      <div class="panel-head">
        <span class="panel-title">{{ selected.ticketNo }} · {{ selected.supplier }}</span>
        <span class="panel-desc">品质三指标由 record 组件整体承载（@CompositeType）</span>
      </div>
      <div class="q-row">
        <span class="q-chip">糖度 {{ selected.quality.brix }}°</span>
        <span class="q-chip">纯度 {{ selected.quality.purity }}%</span>
        <span class="q-chip">纤维 {{ selected.quality.fiber }}%</span>
        <span class="q-chip">扣杂率 {{ selected.deductionRate }}%</span>
      </div>
      <div class="op-row">
        <button class="act primary" :disabled="selected.status !== '待验'" @click="accept(selected.id)">验收</button>
        <button class="act" :disabled="selected.status !== '待验'" @click="reject(selected.id)">拒收</button>
        <span class="st">{{ selected.status }}</span>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">新增过磅</span>
        <span class="panel-desc">净重实时算给你看：{{ previewNet }} 千克，扣杂率 {{ previewRate }}%</span>
      </div>
      <div class="form-row">
        <input v-model="form.ticketNo" class="fld" placeholder="磅单号" />
        <input v-model="form.supplier" class="fld" placeholder="蔗农/合作社" />
        <input v-model="form.truckNo" class="fld" placeholder="车牌" style="width:110px" />
        <input v-model.number="form.grossKg" class="fld n" placeholder="毛重" />
        <input v-model.number="form.tareKg" class="fld n" placeholder="皮重" />
        <input v-model.number="form.deductionKg" class="fld n" placeholder="扣杂" />
      </div>
      <div class="form-row">
        <input v-model.number="form.quality.brix" class="fld n" placeholder="糖度" />
        <input v-model.number="form.quality.purity" class="fld n" placeholder="纯度" />
        <input v-model.number="form.quality.fiber" class="fld n" placeholder="纤维分" />
        <button class="act primary" @click="create">登记过磅</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.filter-row { display: flex; gap: 12px; margin: 10px 0; }
.filter-row label { font-size: 12px; color: var(--muted); display: flex; align-items: center; gap: 6px; }

.retry { margin-left: 10px; }
.empty { font-size: 12px; color: var(--muted); padding: 8px 2px; align-self: center; }

.scale-row { display: flex; gap: 14px; flex-wrap: wrap; align-items: flex-end; }

.scale { width: 92px; text-align: center; cursor: pointer; padding: 6px; border-radius: 9px; border: 1px solid transparent; }
.scale:hover { border-color: var(--primary-soft); }
.scale.on { border-color: var(--primary); background: var(--primary-wash); }

.col {
  height: 150px; display: flex; flex-direction: column; justify-content: flex-end;
  background: #f2f4e8; border-radius: 6px; overflow: hidden; border: 1px solid var(--line);
}
.seg { width: 100%; }
.seg.net { background: linear-gradient(180deg, var(--primary-soft), var(--primary)); }
.seg.tare { background: #cfd6c2; }
.seg.ded {
  background: repeating-linear-gradient(45deg, #e57373, #e57373 5px, #ef9a9a 5px, #ef9a9a 10px);
}

.s-no { font-size: 11px; font-weight: 700; color: var(--primary-deep); margin-top: 5px; }
.s-net { font-size: 11px; color: var(--muted); }
.s-res { font-size: 10px; font-weight: 800; padding: 1px 6px; border-radius: 8px; margin-top: 3px; display: inline-block; }
.s-res.r-合格 { background: #e8f5e9; color: #2e7d32; }
.s-res.r-扣杂 { background: #fff8e1; color: #f57f17; }
.s-res.r-拒收 { background: #ffebee; color: #c62828; }

.legend { display: flex; gap: 16px; font-size: 12px; color: var(--muted); margin-top: 10px; }
.lg { display: flex; align-items: center; gap: 5px; }
.sw { width: 12px; height: 12px; border-radius: 3px; display: inline-block; }
.sw.net { background: var(--primary); }
.sw.tare { background: #cfd6c2; }
.sw.ded { background: #e57373; }

.q-row { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.q-chip { font-size: 12px; padding: 4px 12px; border-radius: 14px; background: var(--primary-wash); color: var(--primary-deep); font-weight: 700; }
.op-row { display: flex; gap: 8px; align-items: center; }
.op-row .st { font-size: 12px; color: var(--muted); }

.form-row { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; margin-bottom: 8px; }
</style>
