// 甘蔗进厂页筛选逻辑单测（node:test，无需安装依赖：node --test test/）
// 覆盖：单条件、双条件、空结果、快速切换、刷新还原、请求失败。
import { test } from 'node:test'
import assert from 'node:assert/strict'

import {
  INTAKE_STATUSES,
  INTAKE_RESULTS,
  buildIntakeParams,
  intakeFiltersFromQuery,
  createLoadGate,
  intakeLoadErrorMessage
} from '../src/utils/intakeQuery.js'

test('词典与后端口径一致', () => {
  assert.deepEqual(INTAKE_STATUSES, ['待验', '已验收', '已拒收'])
  assert.deepEqual(INTAKE_RESULTS, ['合格', '扣杂', '拒收'])
})

test('单条件：只带已选择的条件，未选择的不参与限制', () => {
  assert.deepEqual(buildIntakeParams({ status: '待验', result: '' }), { status: '待验' })
  assert.deepEqual(buildIntakeParams({ status: '', result: '合格' }), { result: '合格' })
})

test('双条件：两个条件一起发出，由后端按「同时满足」过滤', () => {
  assert.deepEqual(
    buildIntakeParams({ status: '已验收', result: '合格' }),
    { status: '已验收', result: '合格' }
  )
})

test('空结果场景不附加任何限制参数，也不过期回退', () => {
  // 无条件时参数为空对象（即查询全部）；合法空结果由后端返回空列表，
  // 前端不存在「空结果回退为全部」的第二套参数
  assert.deepEqual(buildIntakeParams({ status: '', result: '' }), {})
  assert.deepEqual(buildIntakeParams({}), {})
  assert.deepEqual(buildIntakeParams(null), {})
})

test('刷新还原：带条件的地址解析回同一批筛选，往返一致', () => {
  // 直接打开 /#/intake?status=已验收&result=合格
  assert.deepEqual(
    intakeFiltersFromQuery({ status: '已验收', result: '合格' }),
    { status: '已验收', result: '合格' }
  )
  // 筛选 → 地址 → 筛选 往返后仍是同一批条件
  const f = { status: '待验', result: '扣杂' }
  assert.deepEqual(intakeFiltersFromQuery(buildIntakeParams(f)), f)
})

test('清空某一项只移除对应条件，其余条件保留', () => {
  const query = buildIntakeParams({ status: '', result: '拒收' })
  assert.deepEqual(query, { result: '拒收' })
  assert.ok(!('status' in query))
})

test('非法 / 缺失 / 重复的地址参数按未选择处理', () => {
  assert.deepEqual(
    intakeFiltersFromQuery({ status: '不存在的状态', result: '合格' }),
    { status: '', result: '合格' }
  )
  assert.deepEqual(intakeFiltersFromQuery({}), { status: '', result: '' })
  assert.deepEqual(intakeFiltersFromQuery(null), { status: '', result: '' })
  assert.deepEqual(
    intakeFiltersFromQuery({ status: ['待验', '已验收'] }),
    { status: '', result: '' }
  )
})

test('快速切换：较早发出的慢响应不得覆盖最后一次选择', async () => {
  const gate = createLoadGate()
  const state = { rows: [] }
  // 模拟接口：旧条件（已验收）响应慢，新条件（待验）响应快
  const fakeList = (p) => new Promise((resolve) => {
    const stale = p.status === '已验收'
    setTimeout(() => resolve(stale ? ['旧条件的慢响应'] : ['新条件的快响应']), stale ? 30 : 1)
  })
  // 与 IntakeView.load 相同的落地逻辑
  async function load(params) {
    const token = gate.next()
    const rows = await fakeList(params)
    if (!gate.isCurrent(token)) return
    state.rows = rows
  }
  await Promise.all([load({ status: '已验收' }), load({ status: '待验' })])
  assert.deepEqual(state.rows, ['新条件的快响应'])
})

test('请求失败：提示保留当前条件并可按原条件重试', () => {
  const msg = intakeLoadErrorMessage(new Error('Network Error'))
  assert.match(msg, /Network Error/)
  assert.match(msg, /筛选条件已保留/)
  assert.match(msg, /重试/)
  assert.equal(typeof intakeLoadErrorMessage(null), 'string')
})
