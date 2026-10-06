// ------------------------------------------------------------------
// 压榨机组页：状态机可执行动作、检修派生信息、操作互斥与重试的纯逻辑。
// 抽成纯函数模块，PressView.vue 与 node:test 单测（test/pressControl.test.js）
// 共用同一份口径，保证页面按钮、提示与后端状态迁移规则始终一致。
// ------------------------------------------------------------------

/** 机组状态词典（与后端 Dict.PRESS_STATUSES 对齐） */
export const PRESS_STATUSES = ['运行', '停机', '检修']

/** 状态迁移表（与后端 Dict.PRESS_NEXT 对齐）：key=当前状态，value=允许转到的状态 */
export const PRESS_NEXT = {
  运行: ['停机', '检修'],
  停机: ['运行', '检修'],
  检修: ['停机']
}

/** 动作定义：key → {目标状态，按钮文案，类型（负荷/切换）} */
export const PRESS_ACTIONS = {
  setLoad: { kind: 'load', label: '设定负荷' },
  stop: { kind: 'transfer', target: '停机', label: '停机' },
  start: { kind: 'transfer', target: '运行', label: '开机' },
  maintain: { kind: 'transfer', target: '检修', label: '转检修' },
  finishMaintain: { kind: 'transfer', target: '停机', label: '检修完成' }
}

export function isValidPressStatus(s) {
  return typeof s === 'string' && PRESS_STATUSES.includes(s)
}

/**
 * 当前状态下允许迁移到的目标状态列表；非法 / 未知状态返回空列表（一切动作禁用）。
 */
export function allowedTargets(status) {
  return isValidPressStatus(status) ? PRESS_NEXT[status] : []
}

/**
 * 判断某动作此刻是否可执行，并给出禁用原因（可执行时原因为空串）。
 *
 * 规则：
 *  - 设定负荷：只有「运行」可执行；检修中既不能调负荷也不能开机 / 再进检修；
 *  - 转检修：运行 / 停机可进，检修中禁止（不能重复进入检修）；
 *  - 开机（停机→运行）：只有停机可执行；
 *  - 停机（运行→停机）与检修完成（检修→停机）按迁移表判定；
 *  - 任何动作在该机组已有操作进行中（pending）时一律禁用，挡住重复点击 / 竞态。
 */
export function actionState(press, action, pending = false, draftLoad = null) {
  if (pending) return { enabled: false, reason: '操作进行中…' }
  if (!press || !isValidPressStatus(press.status)) {
    return { enabled: false, reason: '机组状态未知，请先刷新' }
  }
  const act = PRESS_ACTIONS[action]
  if (!act) return { enabled: false, reason: '未知动作' }

  const status = press.status
  if (act.kind === 'load') {
    if (status !== '运行') {
      return { enabled: false, reason: maintainBlock(status) }
    }
    const capacity = Number(press.designCapacityTph)
    const load = Number(draftLoad == null ? press.currentLoadTph : draftLoad)
    if (!Number.isFinite(load) || load < 0) {
      return { enabled: false, reason: '负荷必须是不小于 0 的数字' }
    }
    if (load > capacity) {
      return { enabled: false, reason: `负荷不能超过设计处理量 ${capacity} 吨/小时` }
    }
    return { enabled: true, reason: '' }
  }

  const target = act.target
  if (target === '运行' && status === '检修') {
    return { enabled: false, reason: '检修中的机组不能直接开机，需先检修完成回到停机' }
  }
  if (target === '检修' && status === '检修') {
    return { enabled: false, reason: '机组已在检修中，不能重复进入检修' }
  }
  if (!allowedTargets(status).includes(target)) {
    return { enabled: false, reason: `不允许从「${status}」转为「${target}」` }
  }
  return { enabled: true, reason: '' }
}

function maintainBlock(status) {
  if (status === '检修') return '检修中的机组不能调负荷，需先检修完成回到停机'
  if (status === '停机') return '只有「运行」的机组能调负荷，请先开机'
  return '只有「运行」的机组能调负荷'
}

/**
 * 非法转换的失败原因（与后端 rejectReason 口径一致），供页面解释为什么保留原状态。
 */
export function rejectReason(from, target) {
  if (from === '检修') {
    if (target === '运行') return '检修中的机组不能直接开机，需先「检修完成」回到停机'
    if (target === '检修') return '机组已在检修中，不能重复进入检修'
    return '检修中的机组只允许「检修完成」回到停机'
  }
  return `不允许从「${from}」转为「${target}」`
}

/** 机组是否处于检修状态 */
export function isMaintaining(press) {
  return !!press && press.status === '检修'
}

/**
 * 最近检修时间展示：检修时间存在则原样返回；没有记录返回空串。
 * 检修中机组额外提示「正在检修，本次检修开始于 …」。
 */
export function maintainInfo(press) {
  if (!press) return { text: '', active: false }
  const at = press.lastMaintainAt || ''
  if (press.status === '检修') {
    return { text: at ? `正在检修（本次检修开始于 ${formatTime(at)}）` : '正在检修', active: true }
  }
  return { text: at ? `最近检修：${formatTime(at)}` : '暂无检修记录', active: false }
}

/** ISO 时间 → 页面展示的本地时间（yyyy-MM-dd HH:mm），后端返回形如 2026-10-06T09:00:00 */
export function formatTime(v) {
  if (!v) return ''
  const s = String(v).replace('T', ' ')
  return s.length > 16 ? s.slice(0, 16) : s
}

/**
 * 生成一次操作意图的幂等键。同一意图（含失败后的安全重试）必须复用同一个键，
 * 新的意图（新一次点击 / 改了目标值）必须换新键。
 */
export function newRequestId() {
  const rnd = globalThis.crypto && typeof globalThis.crypto.randomUUID === 'function'
    ? globalThis.crypto.randomUUID()
    : 'req-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10)
  return rnd
}

/**
 * 机组操作互斥闸：同一机组同一时刻只允许一个写操作在途。
 * 重复点击直接复用在途 Promise（不会发出第二个请求）；不同动作也要排队，
 * 避免「调负荷与转检修同时发生」在页面侧制造竞态。
 */
export function createStationLocks() {
  const inflight = new Map()
  return {
    /** 是否已有操作在途 */
    busy(stationId) {
      return inflight.has(stationId)
    },
    /**
     * 互斥执行：若该机组已有在途操作，直接复用在途 Promise（调用方拿到同一条结果，
     * 不会发出第二个请求，后一个意图应在锁释放后再由用户发起）。
     * 否则执行 producer，期间占用锁，结束（成功 / 失败）后释放。
     */
    async runExclusive(stationId, producer) {
      if (inflight.has(stationId)) return inflight.get(stationId)
      const promise = Promise.resolve().then(producer).finally(() => inflight.delete(stationId))
      inflight.set(stationId, promise)
      return promise
    }
  }
}

/**
 * 操作失败提示：保留最新状态、说明失败原因，并提示可安全重试（重试复用原 requestId）。
 */
export function actionErrorMessage(err, actionLabel) {
  const m = err && err.message ? err.message : '请求失败'
  return `${actionLabel}失败：${m}；机组当前状态已保留，可点击「重试」再试一次`
}
