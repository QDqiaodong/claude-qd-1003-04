import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 20000 })

// 后端统一返回 {ok, message, data}；HTTP 400 的业务异常也要把中文提示带出来
http.interceptors.response.use(
  (response) => response,
  (error) => {
    const message = error?.response?.data?.message || error?.message || '请求失败'
    return Promise.reject(new Error(message))
  }
)

async function call(promise) {
  const response = await promise
  const body = response.data
  if (!body || body.ok === false) {
    throw new Error((body && body.message) || '请求失败')
  }
  return body.data
}

const params = (o) => ({ params: o })

export default {
  // ---------------- 甘蔗进厂 ----------------
  listIntakes: (p) => call(http.get('/cane-intakes', params(p))),
  createIntake: (d) => call(http.post('/cane-intakes', d)),
  acceptIntake: (id) => call(http.post(`/cane-intakes/${id}/accept`)),
  rejectIntake: (id, reason) => call(http.post(`/cane-intakes/${id}/reject`, null, params({ reason }))),
  dailyTon: () => call(http.get('/cane-intakes/daily-ton')),

  // ---------------- 压榨机组 ----------------
  listPresses: (p) => call(http.get('/press-stations', params(p))),
  createPress: (d) => call(http.post('/press-stations', d)),
  setLoad: (id, loadTph) => call(http.post(`/press-stations/${id}/load`, null, params({ loadTph }))),
  // requestId 是幂等键：一次流转意图重复点击 / 超时重试必须带同一个
  transferPress: (id, target, requestId) =>
    call(http.post(`/press-stations/${id}/transfer`, null, params({ target, requestId }))),

  // ---------------- 投料交接 ----------------
  listFeedBatches: (p) => call(http.get('/feed-batches', params(p))),
  createFeedBatch: (d) => call(http.post('/feed-batches', d)),
  startFeedBatch: (id) => call(http.post(`/feed-batches/${id}/start`)),
  cancelFeedBatch: (id) => call(http.post(`/feed-batches/${id}/cancel`)),
  completeFeedBatch: (id, actualKg) => call(http.post(`/feed-batches/${id}/complete`, null, params({ actualKg }))),

  // ---------------- 蒸发 / 结晶罐 ----------------
  listPans: (p) => call(http.get('/evap-pans', params(p))),
  createPan: (d) => call(http.post('/evap-pans', d)),
  washPan: (id) => call(http.post(`/evap-pans/${id}/wash`)),
  transferPan: (id, target) => call(http.post(`/evap-pans/${id}/transfer`, null, params({ target }))),
  runningCapacity: () => call(http.get('/evap-pans/running-capacity')),

  // ---------------- 蒸发结晶罐洗罐排程 ----------------
  washQueue: (p) => call(http.get('/pan-washes/queue', params(p))),
  listWashes: (p) => call(http.get('/pan-washes', params(p))),
  scheduleWash: (panId, windowAt) =>
    call(http.post('/pan-washes', null, params({ panId, windowAt }))),
  startWash: (id) => call(http.post(`/pan-washes/${id}/start`)),
  cancelWash: (id) => call(http.post(`/pan-washes/${id}/cancel`)),
  completeWash: (id) => call(http.post(`/pan-washes/${id}/complete`)),
  executeWash: (id) => call(http.post(`/pan-washes/${id}/execute`)),

  // ---------------- 成品糖批次 ----------------
  listBatches: (p) => call(http.get('/sugar-batches', params(p))),
  createBatch: (d) => call(http.post('/sugar-batches', d)),
  inspectBatch: (id, d) => call(http.post(`/sugar-batches/${id}/inspections`, d)),
  listBatchInspections: (id) => call(http.get(`/sugar-batches/${id}/inspections`)),
  storeBatch: (id) => call(http.post(`/sugar-batches/${id}/store`)),
  shipBatch: (id) => call(http.post(`/sugar-batches/${id}/ship`)),
  stockByWarehouse: () => call(http.get('/sugar-batches/stock-by-warehouse'))
}
