import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createBatchListController } from '../src/utils/batchListController.js'

// 手工可控的 mock api：每次调用记录参数并返回一个可由测试决定何时 resolve/reject 的 promise
function createMockApi() {
  const calls = []
  const pending = []
  const api = {
    listBatches(params) {
      calls.push(params)
      const entry = { params }
      entry.promise = new Promise((resolve, reject) => {
        entry.resolve = resolve
        entry.reject = reject
      })
      pending.push(entry)
      return entry.promise
    }
  }
  return { api, calls, pending }
}

function freshState() {
  return {
    status: '', keyword: '', overdueOnly: false,
    page: 1, size: 10, rows: [], total: 0, loading: false, error: ''
  }
}

async function flush() {
  await new Promise((r) => setTimeout(r, 0))
}

test('initial load sends 0-based page 0 with default size and writes rows/total', async () => {
  const { api, calls, pending } = createMockApi()
  const state = freshState()
  const ctl = createBatchListController({ api, state, notify: () => assert.fail('no notify expected') })

  ctl.load()
  pending.at(-1).resolve({ items: [{ id: 1, batchNo: 'XJ-1' }], total: 1, page: 0, size: 10 })
  await flush()

  assert.deepEqual(calls[0], { page: 0, size: 10 })
  assert.equal(state.rows.length, 1)
  assert.equal(state.total, 1)
  assert.equal(state.loading, false)
  assert.equal(state.error, '')
})

test('keyword/status/overdueOnly are all sent together (keyword regression) and search resets to page 1', async () => {
  const { api, calls, pending } = createMockApi()
  const state = freshState()
  const ctl = createBatchListController({ api, state })

  state.page = 3 // 先翻到第 3 页
  ctl.load()
  pending.at(-1).resolve({ items: [], total: 0 })
  await flush()
  assert.equal(calls.at(-1).page, 2)

  // 输入关键字 + 切状态 + 勾选只看过期未执行后查询：必须回到第 1 页，且三个条件同时下发
  state.keyword = 'XJ'
  state.status = '待执行'
  state.overdueOnly = true
  ctl.search()
  await Promise.resolve()
  assert.deepEqual(calls.at(-1), {
    status: '待执行', keyword: 'XJ', overdueOnly: true, page: 0, size: 10
  })
  assert.equal(state.page, 1)
  pending.at(-1).resolve({ items: [{ id: 9, batchNo: 'XJ-9' }], total: 1 })
  await flush()
  assert.equal(state.rows[0].batchNo, 'XJ-9')
})

test('next/prev page keeps every filter; changing size resets to page 1 with filters kept', async () => {
  const { api, calls, pending } = createMockApi()
  const state = Object.assign(freshState(), {
    keyword: '张工', status: '执行中', overdueOnly: false
  })
  const ctl = createBatchListController({ api, state })

  ctl.changePage(2)
  await Promise.resolve()
  assert.deepEqual(calls.at(-1), { status: '执行中', keyword: '张工', page: 1, size: 10 })
  pending.at(-1).resolve({ items: [{ id: 2 }], total: 25 })
  await flush()

  ctl.changePage(3)
  await Promise.resolve()
  assert.deepEqual(calls.at(-1), { status: '执行中', keyword: '张工', page: 2, size: 10 })
  pending.at(-1).resolve({ items: [{ id: 3 }], total: 25 })
  await flush()

  // 改每页条数：筛选保留、回第 1 页、新 size 下发
  ctl.changeSize(50)
  await Promise.resolve()
  assert.deepEqual(calls.at(-1), { status: '执行中', keyword: '张工', page: 0, size: 50 })
  pending.at(-1).resolve({ items: [], total: 25 })
  await flush()

  // 切换「只看过期未执行」同样回第 1 页，且把条件叠加进去
  state.overdueOnly = true
  ctl.search()
  await Promise.resolve()
  assert.deepEqual(calls.at(-1), {
    status: '执行中', keyword: '张工', overdueOnly: true, page: 0, size: 50
  })
  pending.at(-1).resolve({ items: [], total: 0 })
  await flush()
})

test('stale slow response must not overwrite the latest filtered list', async () => {
  const { api, pending } = createMockApi()
  const state = freshState()
  const ctl = createBatchListController({ api, state })

  // 第一次请求（无筛选）迟迟不返回
  ctl.load()
  const slow = pending.at(-1)

  // 用户立刻加了关键字重新查询，第二次先返回
  state.keyword = 'XJ-NEW'
  ctl.search()
  const fast = pending.at(-1)
  fast.resolve({ items: [{ id: 100, batchNo: 'XJ-NEW' }], total: 1 })
  await flush()

  // 旧的慢请求此时才回来：它的数据绝不能覆盖当前筛选结果
  slow.resolve({ items: [{ id: 1, batchNo: 'OLD-UNFILTERED' }], total: 99 })
  await flush()

  assert.deepEqual(state.rows.map((r) => r.batchNo), ['XJ-NEW'])
  assert.equal(state.total, 1)
})

test('query failure clears stale rows and notifies, but preserves entered filters and page', async () => {
  const notifications = []
  const mock = createMockApi()
  const state = Object.assign(freshState(), {
    status: '待整改', keyword: '批次编号XYZ', overdueOnly: true, page: 2,
    rows: [{ id: 1, batchNo: 'OLD' }], total: 1
  })
  const ctl = createBatchListController({ api: mock.api, state, notify: (m) => notifications.push(m) })

  ctl.changePage(2)
  mock.pending.at(-1).reject(new Error('网络异常：批次状态不合法：已报废'))
  await flush()

  assert.deepEqual(state.rows, [])
  assert.equal(state.total, 0)
  assert.equal(state.error, '网络异常：批次状态不合法：已报废')
  assert.deepEqual(notifications, ['网络异常：批次状态不合法：已报废'])
  // 用户已填条件与页码原样保留，可直接重试
  assert.equal(state.keyword, '批次编号XYZ')
  assert.equal(state.status, '待整改')
  assert.equal(state.overdueOnly, true)
  assert.equal(state.page, 2)
  assert.equal(state.loading, false)

  // 直接重试（不重置页码）：参数仍是同一套筛选 + 同一页；成功后错误清除、数据恢复
  ctl.load()
  await Promise.resolve()
  assert.deepEqual(mock.calls.at(-1), {
    status: '待整改', keyword: '批次编号XYZ', overdueOnly: true, page: 1, size: 10
  })
  mock.pending.at(-1).resolve({ items: [{ id: 42, batchNo: 'XJ-42' }], total: 12 })
  await flush()
  assert.equal(state.error, '')
  assert.deepEqual(state.rows.map((r) => r.batchNo), ['XJ-42'])
  assert.equal(state.total, 12)
})

test('successful reload after failure clears the error and reuses the same filter/page', async () => {
  const mock = createMockApi()
  const state = Object.assign(freshState(), {
    keyword: 'XJ', page: 1, rows: [], total: 0, error: '上一轮错误'
  })
  const ctl = createBatchListController({ api: mock.api, state })
  ctl.reload()
  mock.pending.at(-1).resolve({ items: [{ id: 7, batchNo: 'XJ-7' }], total: 1 })
  await flush()
  assert.equal(state.error, '')
  assert.equal(state.rows[0].batchNo, 'XJ-7')
  assert.deepEqual(mock.calls.at(-1), { keyword: 'XJ', page: 0, size: 10 })
})

test('identical concurrent requests are de-duplicated into one call', async () => {
  const { api, calls, pending } = createMockApi()
  const state = freshState()
  const ctl = createBatchListController({ api, state })

  // el-pagination 在某些场景会连放两个事件：同参数在途时只发一次请求
  const p1 = ctl.changePage(2)
  const p2 = ctl.changePage(2)
  assert.equal(calls.length, 1)
  pending.at(-1).resolve({ items: [], total: 0 })
  await Promise.all([p1, p2])
})

test('tenant reset restarts from page 1 and subsequent pages stay tenant-scoped by header (params unchanged)', async () => {
  const { api, calls, pending } = createMockApi()
  const state = Object.assign(freshState(), { page: 5, size: 20 })
  const ctl = createBatchListController({ api, state })

  ctl.resetForTenant()
  await Promise.resolve()
  assert.equal(state.page, 1)
  assert.deepEqual(calls.at(-1), { page: 0, size: 20 })
  pending.at(-1).resolve({ items: [], total: 0 })
  await flush()
})
