// ------------------------------------------------------------------
// 压榨机组页：状态机、可执行动作派生、流转幂等键与失败提示。
// 抽成纯函数模块，PressView.vue 与 node:test 单测（test/pressActions.test.js）
// 共用同一份逻辑，保证页面按钮、重试行为与后端口径始终一致。
// ------------------------------------------------------------------

/** 机组状态词典（与后端 Dict.PRESS_STATUSES 对齐） */
export const PRESS_STATUSES = ['运行', '停机', '检修']

/**
 * 状态机（与后端 Dict.PRESS_NEXT 对齐）：
 * 运行 ↔ 停机；检修只能从运行/停机进，检修完成只能回停机。
 */
const PRESS_NEXT = {
  运行: ['停机', '检修'],
  停机: ['运行', '检修'],
  检修: ['停机']
}

/** 只有「运行」的机组能调负荷 */
export function canSetLoad(status) {
  return status === '运行'
}

export function canTransfer(status, target) {
  return (PRESS_NEXT[status] || []).includes(target)
}

/** 动作按钮文案：检修 → 停机 这一下在值班员口径里就是「检修完成」 */
const ACTION_LABELS = {
  运行: '开机',
  停机: '停机',
  检修: '转检修'
}

/**
 * 可执行动作：完全由服务端状态派生，刷新后按钮与状态必然一致。
 * 检修中的机组只给出「检修完成」（回停机），不会出现调负荷 / 开机 / 再进检修。
 */
export function pressActions(status) {
  if (status === '检修') return [{ target: '停机', label: '检修完成' }]
  return (PRESS_NEXT[status] || []).map((target) => ({ target, label: ACTION_LABELS[target] }))
}

/** 一次流转意图一个幂等键：重复点击 / 超时重试带同一键，后端只生效一次 */
export function newRequestId() {
  return globalThis.crypto && crypto.randomUUID
    ? crypto.randomUUID()
    : String(Date.now()) + '-' + Math.random().toString(16).slice(2)
}

/**
 * 开始一次流转尝试：同一机组同一目标且上一次尝试结果未知（未决）时，
 * 复用同一幂等键重试；否则视为新意图，发新键。
 * pending 形如 { stationId, target, requestId }，没有则为 null。
 */
export function beginAttempt(pending, stationId, target) {
  if (pending && pending.stationId === stationId && pending.target === target) {
    return { attempt: pending, reused: true }
  }
  return { attempt: { stationId, target, requestId: newRequestId() }, reused: false }
}

/**
 * 尝试结束后的未决记录：成功或业务拒绝（后端已明确答复、状态未变）都清除，
 * 下一次点击是新意图；只有结果未知（网络 / 超时）才保留，供同一动作安全重试。
 */
export function nextPendingAfter(attempt, err) {
  return err && isUnknownOutcome(err) ? attempt : null
}

/** 结果未知的失败：请求可能已到达后端但响应丢失（超时 / 断网） */
export function isUnknownOutcome(err) {
  const m = err && err.message ? err.message : ''
  return /timeout|Network Error|ECONNABORTED/i.test(m)
}

/**
 * 流转失败提示：业务拒绝直接透传后端原因（原状态已保留）；
 * 结果未知时说明可安全重试——同一幂等键不会重复记录检修时间。
 */
export function transferErrorMessage(err) {
  const m = err && err.message ? err.message : '操作失败'
  if (isUnknownOutcome(err)) {
    return `${m}；本次结果未知，可安全重试（同一请求幂等，不会重复记录检修时间），或刷新列表确认`
  }
  return m
}

/** 后端 LocalDateTime 序列化为无时区 ISO（yyyy-MM-ddTHH:mm:ss），按本地时间展示 */
export function fmtTime(t) {
  if (!t) return '—'
  const s = String(t).replace('T', ' ')
  return s.length >= 16 ? s.slice(0, 16) : s
}
