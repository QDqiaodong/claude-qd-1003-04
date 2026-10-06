// 压榨机组状态机与操作互斥单测（node:test：node --test test/）
// 覆盖：状态迁移口径、各状态下动作可用性（检修三禁）、非法转换原因、
// 检修信息派生、操作互斥（重复点击复用 / 不同动作串行）、失败重试提示、刷新口径。
import { test } from 'node:test'
import assert from 'node:assert/strict'

import {
  PRESS_STATUSES,
  PRESS_NEXT,
  PRESS_ACTIONS,
  actionState,
  allowedTargets,
  rejectReason,
  isMaintaining,
  maintainInfo,
  formatTime,
  newRequestId,
  createStationLocks,
  actionErrorMessage
} from '../src/utils/pressControl.js'

const press = (over = {}) => ({
  id: 1,
  stationName: '1号压榨机组',
  designCapacityTph: 200,
  currentLoadTph: 100,
  status: '运行',
  lastMaintainAt: '2026-09-01T08:30:00',
  ...over
})

test('词典与状态迁移表与后端 Dict 对齐', () => {
  assert.deepEqual(PRESS_STATUSES, ['运行', '停机', '检修'])
  assert.deepEqual(allowedTargets('运行'), ['停机', '检修'])
  assert.deepEqual(allowedTargets('停机'), ['运行', '检修'])
  assert.deepEqual(allowedTargets('检修'), ['停机'])
  assert.deepEqual(allowedTargets('不存在'), [])
  assert.deepEqual(PRESS_NEXT['检修'], ['停机'], '检修完成只能回停机')
})

test('运行机组：可设定合法负荷、可停机、可转检修', () => {
  const p = press()
  assert.equal(actionState(p, 'setLoad', false, 180).enabled, true)
  assert.equal(actionState(p, 'stop').enabled, true)
  assert.equal(actionState(p, 'maintain').enabled, true)
  // 运行中不能「开机」（只能停机→运行）
  assert.equal(actionState(p, 'start').enabled, false)
  assert.match(actionState(p, 'start').reason, /不允许从「运行」转为「运行」/)
})

test('停机机组：可开机、可转检修，但不能调负荷', () => {
  const p = press({ status: '停机', currentLoadTph: 0 })
  assert.equal(actionState(p, 'start').enabled, true)
  assert.equal(actionState(p, 'maintain').enabled, true)
  assert.equal(actionState(p, 'setLoad', false, 10).enabled, false)
  assert.match(actionState(p, 'setLoad', false, 10).reason, /请先开机/)
  assert.equal(actionState(p, 'stop').enabled, false)
})

test('检修三禁：不能调负荷、不能开机、不能再次进入检修；只能检修完成回停机', () => {
  const p = press({ status: '检修', currentLoadTph: 0 })
  assert.equal(actionState(p, 'setLoad', false, 10).enabled, false)
  assert.match(actionState(p, 'setLoad', false, 10).reason, /检修中的机组不能调负荷/)
  assert.equal(actionState(p, 'start').enabled, false)
  assert.match(actionState(p, 'start').reason, /不能直接开机/)
  assert.equal(actionState(p, 'maintain').enabled, false)
  assert.match(actionState(p, 'maintain').reason, /不能重复进入检修/)
  assert.equal(actionState(p, 'finishMaintain').enabled, true, '检修完成是唯一出路')
  assert.equal(actionState(p, 'stop').enabled, true, '停机即检修完成，迁移表允许')
})

test('超载（超过设计处理量）时设定负荷禁用并给原因', () => {
  const p = press()
  const st = actionState(p, 'setLoad', false, 205)
  assert.equal(st.enabled, false)
  assert.match(st.reason, /不能超过设计处理量/)
  // 负数 / 非数字同样禁用
  assert.equal(actionState(p, 'setLoad', false, -1).enabled, false)
  assert.equal(actionState(p, 'setLoad', false, NaN).enabled, false)
})

test('pending（操作在途）时一切动作禁用，挡住重复点击', () => {
  const p = press()
  for (const action of Object.keys(PRESS_ACTIONS)) {
    assert.equal(actionState(p, action, true).enabled, false, `${action} 应禁用`)
    assert.equal(actionState(p, action, true).reason, '操作进行中…')
  }
})

test('未知状态 / 空机组：动作全禁用并提示刷新', () => {
  const st = actionState(press({ status: '未知' }), 'setLoad')
  assert.equal(st.enabled, false)
  assert.match(st.reason, /刷新/)
  assert.equal(actionState(null, 'start').enabled, false)
})

test('非法转换原因与后端口径一致，并明确保留原状态', () => {
  assert.match(rejectReason('检修', '运行'), /不能直接开机/)
  assert.match(rejectReason('检修', '检修'), /不能重复进入检修/)
  assert.match(rejectReason('运行', '运行'), /不允许从「运行」转为「运行」/)
})

test('检修信息派生：检修中标本次开始时间，非检修显示最近检修时间', () => {
  const active = maintainInfo(press({ status: '检修', lastMaintainAt: '2026-10-06T09:00:00' }))
  assert.equal(active.active, true)
  assert.match(active.text, /正在检修/)
  assert.match(active.text, /2026-10-06 09:00/)

  const stopped = maintainInfo(press({ status: '停机' }))
  assert.equal(stopped.active, false)
  assert.match(stopped.text, /最近检修/)

  assert.equal(maintainInfo(press({ lastMaintainAt: null, status: '停机' })).text, '暂无检修记录')
  assert.equal(isMaintaining(press({ status: '检修' })), true)
  assert.equal(isMaintaining(press({ status: '运行' })), false)
  assert.equal(formatTime('2026-10-06T09:00:00'), '2026-10-06 09:00')
  assert.equal(formatTime(''), '')
})

test('requestId：每次意图唯一，重试复用原键', () => {
  const a = newRequestId()
  const b = newRequestId()
  assert.ok(typeof a === 'string' && a.length > 0)
  assert.notEqual(a, b, '两次意图必须用不同幂等键')
})

test('操作互斥：同一机组并发调用只执行一次生产者，重复点击拿到同一结果', async () => {
  const locks = createStationLocks()
  let calls = 0
  const producer = async () => {
    calls += 1
    await new Promise((r) => setTimeout(r, 5))
    return { status: '检修', currentLoadTph: 0 }
  }
  assert.equal(locks.busy(1), false)
  const [r1, r2, r3] = await Promise.all([
    locks.runExclusive(1, producer),
    locks.runExclusive(1, producer),
    locks.runExclusive(1, producer)
  ])
  assert.equal(calls, 1, '在途期间重复点击不得发出第二个请求')
  assert.deepEqual(r1, r2)
  assert.deepEqual(r2, r3)
  assert.equal(locks.busy(1), false, '结束后锁释放，可进行下一次操作')
})

test('调负荷与转检修不会在同一机组同时发出：并发点击合并为同一在途请求', async () => {
  const locks = createStationLocks()
  const order = []
  const loadOp = () => locks.runExclusive(1, async () => {
    order.push('load-start')
    await new Promise((r) => setTimeout(r, 20))
    order.push('load-end')
    return 'load'
  })
  const transferOp = () => locks.runExclusive(1, async () => {
    order.push('transfer-start')
    await new Promise((r) => setTimeout(r, 1))
    order.push('transfer-end')
    return 'transfer'
  })
  const [a, b] = await Promise.all([loadOp(), transferOp()])
  // 后到者复用先到者的在途 Promise：两个并发点击只有一个真正发出
  assert.equal(a, b)
  assert.deepEqual(order, ['load-start', 'load-end'], '临界区内只允许一个操作')
})

test('一个操作结束后，同机组的下一个操作可正常进入（串行衔接）', async () => {
  const locks = createStationLocks()
  const first = await locks.runExclusive(1, async () => {
    await new Promise((r) => setTimeout(r, 5))
    return '检修'
  })
  const second = await locks.runExclusive(1, async () => '停机')
  assert.equal(first, '检修')
  assert.equal(second, '停机')
})

test('不同机组互不阻塞', async () => {
  const locks = createStationLocks()
  const active = new Set()
  let maxConcurrent = 0
  const run = (id) => locks.runExclusive(id, async () => {
    active.add(id)
    maxConcurrent = Math.max(maxConcurrent, active.size)
    await new Promise((r) => setTimeout(r, 5))
    active.delete(id)
    return id
  })
  const results = await Promise.all([run(1), run(2), run(3)])
  assert.deepEqual(results, [1, 2, 3])
  assert.equal(maxConcurrent, 3, '不同机组应能并行')
})

test('失败也会释放锁，允许安全重试；错误提示保留状态并引导重试', async () => {
  const locks = createStationLocks()
  await assert.rejects(
    locks.runExclusive(1, async () => { throw new Error('Network Error') }),
    /Network Error/
  )
  assert.equal(locks.busy(1), false, '失败后锁必须释放')
  // 下一次操作（重试）可以进入
  const again = await locks.runExclusive(1, async () => 'retried')
  assert.equal(again, 'retried')

  const msg = actionErrorMessage(new Error('系统繁忙'), '转检修')
  assert.match(msg, /转检修失败/)
  assert.match(msg, /当前状态已保留/)
  assert.match(msg, /重试/)
})
