// ------------------------------------------------------------------
// 甘蔗进厂页：筛选条件 ↔ 接口参数 ↔ 地址查询参数的换算，以及加载序列闸。
// 抽成纯函数模块，IntakeView.vue 与 node:test 单测（test/intakeQuery.test.js）
// 共用同一份逻辑，保证页面、地址栏与接口口径始终一致。
// ------------------------------------------------------------------

/** 验收状态词典（与后端 Dict.INTAKE_STATUSES 对齐） */
export const INTAKE_STATUSES = ['待验', '已验收', '已拒收']

/** 品质结论词典（与后端 Dict.INTAKE_RESULTS 对齐） */
export const INTAKE_RESULTS = ['合格', '扣杂', '拒收']

/**
 * 筛选 → 接口参数 / 地址查询参数：只带上已选择的条件。
 * 未选择（空）的条件不参与限制；清空某一项即移除对应参数。
 */
export function buildIntakeParams(filters) {
  const f = filters || {}
  const p = {}
  if (f.status) p.status = f.status
  if (f.result) p.result = f.result
  return p
}

/**
 * 地址查询参数 → 筛选：只接受词典内的值，非法 / 缺失 / 重复（数组）参数按「未选择」处理。
 * 直接打开带条件的地址或刷新后，还原出的仍是同一批条件。
 */
export function intakeFiltersFromQuery(query) {
  const q = query || {}
  const status = typeof q.status === 'string' ? q.status : ''
  const result = typeof q.result === 'string' ? q.result : ''
  return {
    status: INTAKE_STATUSES.includes(status) ? status : '',
    result: INTAKE_RESULTS.includes(result) ? result : ''
  }
}

/**
 * 加载序列闸：每次加载取一个递增令牌，只有持最后一次令牌的响应允许落地。
 * 值班员连续快速切换条件时，较早发出的慢响应不得覆盖最后一次选择。
 * 实现已抽到 utils/loadGate.js（PressView 等同款需求共用），这里保持再导出。
 */
export { createLoadGate } from './loadGate.js'

/**
 * 加载失败提示：明确告知筛选条件已保留，可按原条件重试。
 */
export function intakeLoadErrorMessage(err) {
  const m = err && err.message ? err.message : '请求失败'
  return `加载失败：${m}；当前筛选条件已保留，可点击「重试」按原条件重新加载`
}
