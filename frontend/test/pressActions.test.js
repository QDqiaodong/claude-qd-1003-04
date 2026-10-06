// 压榨机组页状态机与流转幂等逻辑单测（node:test，无需安装依赖：node --test test/）
// 覆盖：状态迁移派生、刷新还原、重复点击、超时重试、并发竞态落地、异常反馈。
import { test } from 'node:test'
import assert from 'node:assert/strict'

import {
  PRESS_STATUSES,
  canSetLoad,
  canTransfer,
  pressActions,
  newRequestId,
  beginAttempt,
  nextPendingAfter,
  isUnknownOutcome,
  transferErrorMessage,
  fmtTime
} from '../src/utils/pressActions.js'

test('词典与状态机同后端 Dict 对齐', () => {
  assert.deepEqual(PRESS_STATUSES, ['运行', '停机', '检修'])
  // 运行 ↔ 停机；运行/停机 → 检修；检修完成只能回停机
  assert.deepEqual(
    ['停机', '检修'].filter((t) => canTransfer('运行', t)), ['停机', '检修'])
  assert.deepEqual(
    ['运行', '检修'].filter((t) => canTransfer('停机', t)), ['运行', '检修'])
  assert.ok(canTransfer('检修', '停机'))
  // 检修中不能开机、不能再次进入检修；同状态不算流转
  assert.ok(!canTransfer('检修', '运行'))
  assert.ok(!canTransfer('检修', '检修'))
  assert.ok(!canTransfer('运行', '运行'))
  assert.ok(!canTransfer('停机', '停机'))
  assert.ok(!canTransfer('不存在', '停机'))
})

test('可执行动作完全由服务端状态派生（刷新还原后按钮与状态一致）', () => {
  assert.deepEqual(pressActions('运行'), [
    { target: '停机', label: '停机' },
    { target: '检修', label: '转检修' }
  ])
  assert.deepEqual(pressActions('停机'), [
    { target: '运行', label: '开机' },
    { target: '检修', label: '转检修' }
  ])
  // 检修中唯一动作是「检修完成」回停机：不会渲染调负荷 / 开机 / 再进检修
  assert.deepEqual(pressActions('检修'), [{ target: '停机', label: '检修完成' }])
  assert.deepEqual(pressActions('未知状态'), [])
  // 调负荷仅运行中
  assert.equal(canSetLoad('运行'), true)
  assert.equal(canSetLoad('停机'), false)
  assert.equal(canSetLoad('检修'), false)
})

test('重复点击 / 超时重试：同一未决尝试复用同一幂等键', () => {
  let pending = null
  const a1 = beginAttempt(pending, 7, '检修')
  assert.equal(a1.reused, false)
  assert.ok(a1.attempt.requestId)

  // 第一次尝试超时（结果未知）→ 未决记录保留
  pending = nextPendingAfter(a1.attempt, new Error('timeout of 20000ms exceeded'))
  assert.deepEqual(pending, a1.attempt)

  // 重试同一动作 → 复用同一键，后端据此幂等回放，不重复记录检修时间
  const a2 = beginAttempt(pending, 7, '检修')
  assert.equal(a2.reused, true)
  assert.equal(a2.attempt.requestId, a1.attempt.requestId)

  // 换目标 / 换机组 → 新意图发新键
  assert.equal(beginAttempt(pending, 7, '停机').reused, false)
  assert.equal(beginAttempt(pending, 8, '检修').reused, false)
})

test('成功或业务拒绝后清除未决：下一次点击是新意图', () => {
  const a1 = beginAttempt(null, 7, '检修').attempt
  // 成功 → 清除
  assert.equal(nextPendingAfter(a1, null), null)
  // 业务拒绝（后端已明确答复、原状态保留）→ 清除，重试同一非法操作无意义
  assert.equal(nextPendingAfter(a1, new Error('不允许从「检修」转为「运行」')), null)
  // 结果未知才保留
  assert.deepEqual(nextPendingAfter(a1, new Error('Network Error')), a1)
})

test('结果未知判定与失败提示', () => {
  assert.equal(isUnknownOutcome(new Error('timeout of 20000ms exceeded')), true)
  assert.equal(isUnknownOutcome(new Error('Network Error')), true)
  assert.equal(isUnknownOutcome(new Error('不允许从「检修」转为「运行」')), false)
  assert.equal(isUnknownOutcome(null), false)

  // 业务拒绝：透传后端原因（原状态已保留）
  const biz = transferErrorMessage(new Error('不允许从「检修」转为「运行」：检修完成只能回到「停机」'))
  assert.match(biz, /检修完成只能回到「停机」/)
  assert.ok(!biz.includes('幂等'))

  // 结果未知：明确告知可安全重试且不会重复记录检修时间
  const unknown = transferErrorMessage(new Error('timeout of 20000ms exceeded'))
  assert.match(unknown, /结果未知/)
  assert.match(unknown, /可安全重试/)
  assert.match(unknown, /不会重复记录检修时间/)
})

test('最近检修时间格式化', () => {
  assert.equal(fmtTime(null), '—')
  assert.equal(fmtTime(''), '—')
  assert.equal(fmtTime('2026-10-06T08:30:00'), '2026-10-06 08:30')
  assert.equal(fmtTime('2026-10-06T08:30'), '2026-10-06 08:30')
})

test('幂等键每次生成不同', () => {
  assert.notEqual(newRequestId(), newRequestId())
})

// ------------------------------------------------------------------
// 端到端模拟：视图层落地逻辑（与 PressView.transfer 相同）+ 假后端
// （行锁串行 + requestId 幂等回放），验证「只能落下一条一致结果」。
// ------------------------------------------------------------------

function makeFakeBackend(state) {
  const seen = new Map() // requestId -> 已受理
  const calls = []
  return {
    calls,
    // lostResponse=true 模拟「已受理但响应丢失」（客户端超时）
    async transfer(id, target, requestId, { lostResponse = false } = {}) {
      calls.push({ id, target, requestId })
      if (!seen.has(requestId)) {
        // 非法转换：保留原状态并说明原因
        const next = { 运行: ['停机', '检修'], 停机: ['运行', '检修'], 检修: ['停机'] }
        if (!(next[state.status] || []).includes(target)) {
          throw new Error(`不允许从「${state.status}」转为「${target}」`)
        }
        if (target === '检修') {
          state.load = 0
          state.maintainAt = '2026-10-06 12:00'
        }
        state.status = target
        seen.set(requestId, true)
      }
      // 同一键重试：幂等回放当前状态，不重复记录检修时间
      const result = { ...state }
      if (lostResponse) throw new Error('timeout of 20000ms exceeded')
      return result
    }
  }
}

function makeView(state, backend) {
  // 与 PressView 相同的落地逻辑：busy 拦重入 + 未决键复用 + 失败后回拉
  const view = {
    busy: false,
    pending: null,
    error: '',
    async transfer(id, target, opts) {
      if (view.busy) return 'ignored'
      view.busy = true
      const { attempt } = beginAttempt(view.pending, id, target)
      view.pending = attempt
      try {
        const r = await backend.transfer(id, target, attempt.requestId, opts)
        view.pending = nextPendingAfter(attempt, null)
        return r
      } catch (e) {
        view.pending = nextPendingAfter(attempt, e)
        view.error = transferErrorMessage(e)
        throw e
      } finally {
        view.busy = false
      }
    }
  }
  return view
}

test('双击转检修：第二次被 busy 拦下，后端只见一个请求', async () => {
  const state = { status: '运行', load: 120, maintainAt: null }
  const backend = makeFakeBackend(state)
  const view = makeView(state, backend)

  const [r1, r2] = await Promise.all([view.transfer(1, '检修'), view.transfer(1, '检修')])
  assert.equal(r2, 'ignored', '进行中重复点击必须被拦下')
  assert.equal(backend.calls.length, 1)
  assert.equal(state.status, '检修')
  assert.equal(state.load, 0, '转检修后负荷必须归零')
  assert.ok(state.maintainAt, '必须记录本次检修时间')
  assert.equal(r1.maintainAt, state.maintainAt)
})

test('请求超时（已受理但响应丢失）：重试复用同一键，检修时间不被覆盖', async () => {
  const state = { status: '运行', load: 120, maintainAt: null }
  const backend = makeFakeBackend(state)
  const view = makeView(state, backend)

  // 第一次：后端已受理并落库，但响应丢失 → 客户端看到超时
  await assert.rejects(view.transfer(1, '检修', { lostResponse: true }), /timeout/)
  assert.equal(state.status, '检修', '后端实际已转为检修')
  const t1 = state.maintainAt
  assert.match(view.error, /可安全重试/)

  // 值班员重试同一动作：复用同一幂等键 → 后端回放，不重复记录检修时间
  const r = await view.transfer(1, '检修')
  assert.equal(backend.calls.length, 2)
  assert.equal(backend.calls[0].requestId, backend.calls[1].requestId)
  assert.equal(state.maintainAt, t1, '检修时间必须保持第一次落下的值')
  assert.equal(r.maintainAt, t1)
  assert.equal(view.pending, null, '成功后清除未决记录')
})

test('非法转换：保留原状态并说明原因，业务拒绝后重试合法动作可成功', async () => {
  const state = { status: '检修', load: 0, maintainAt: '2026-10-06 12:00' }
  const backend = makeFakeBackend(state)
  const view = makeView(state, backend)

  // 检修中开机 → 拒绝，原因透传，状态不变
  await assert.rejects(view.transfer(1, '运行'), /不允许从「检修」转为「运行」/)
  assert.equal(state.status, '检修')
  assert.equal(view.pending, null, '业务拒绝后清除未决，重试发新键')

  // 检修完成回停机 → 成功
  const r = await view.transfer(1, '停机')
  assert.equal(r.status, '停机')
  assert.equal(state.maintainAt, '2026-10-06 12:00', '检修时间保留')
})
