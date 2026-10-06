// 安全巡检批次列表的状态控制器：不依赖 Vue，可直接用 Node 内置测试覆盖。
//
// 本次修复要保证的「分页条件一致性」全部收敛在这里：
//  1. search()（点查询 / 改关键字 / 切状态 / 勾「只看过期未执行」/ 改每页条数 / 切租户）
//     ——筛选生效后一律回到第 1 页重新计算；
//  2. changePage()（上一页/下一页/跳页）——keyword/status/overdueOnly 原样保留，只动页码；
//  3. 每次请求的参数都由 buildListParams 从同一个 state 装配，杜绝旧字段名（query.key）漏传；
//  4. 慢响应保护：只有最后一次请求允许落地，避免先发的慢请求把上一轮筛选结果刷回列表；
//  5. 查询失败：清空上一轮数据并提示原因，但保留用户已填的筛选条件与页码，直接可重试。

import { buildListParams, clampSize } from './batchQuery.js'

function clampPage(page) {
  const n = Number(page)
  if (!Number.isFinite(n)) return 1
  return Math.max(1, Math.floor(n))
}

export function createBatchListController({ api, state, notify }) {
  // 单调递增的请求序号：只允许序号最新的那次请求写回列表
  let activeSeq = 0
  // 相同参数的在途请求去重（el-pagination 在某些版本会连放两个事件）
  let inFlight = null

  async function requestList() {
    const params = buildListParams(state)
    const key = JSON.stringify(params)
    // 仅当在途请求仍是「最新一次」（参数也一致）时才复用；
    // 它若已被更新的筛选/翻页请求取代，则不能拿旧 promise 冒充本次结果
    if (inFlight && inFlight.key === key && inFlight.seq === activeSeq) {
      return inFlight.promise
    }

    const seq = ++activeSeq
    state.loading = true
    state.error = ''

    const promise = api
      .listBatches(params)
      .then((data) => {
        // 筛选/页码已更新后才返回的旧响应一律丢弃，不能混进当前列表
        if (seq !== activeSeq) return
        state.rows = Array.isArray(data?.items) ? data.items : []
        state.total = Number.isFinite(data?.total) ? data.total : 0
      })
      .catch((e) => {
        if (seq !== activeSeq) return
        // 失败不保留上一轮旧数据，但保留用户已填条件（state.status/keyword/... 不动）
        state.rows = []
        state.total = 0
        state.error = e?.message || '查询失败'
        if (typeof notify === 'function') notify(state.error)
      })
      .finally(() => {
        if (seq === activeSeq) state.loading = false
        if (inFlight && inFlight.seq === seq) inFlight = null
      })

    inFlight = { key, seq, promise }
    return promise
  }

  /** 点「查询」/ 改关键字 / 切状态 / 勾选「只看过期未执行」：从第 1 页重新计算。 */
  function search() {
    state.page = 1
    return requestList()
  }

  /** 上一页 / 下一页 / 跳页：筛选条件保留，只切页码（后端 0 基由 buildListParams 换算）。 */
  function changePage(page) {
    state.page = clampPage(page)
    return requestList()
  }

  /** 修改每页条数：筛选条件保留，回到第 1 页重新分页。 */
  function changeSize(size) {
    state.size = clampSize(size)
    state.page = 1
    return requestList()
  }

  /** 切换租户（片区）：列表从第 1 页重新加载；租户由请求头保证，不进查询参数。 */
  function resetForTenant() {
    state.page = 1
    return requestList()
  }

  /** 详情/创建/隐患闭环引起数据变化后的原地刷新，条件与页码都不动。 */
  function reload() {
    return requestList()
  }

  return { load: requestList, search, changePage, changeSize, resetForTenant, reload }
}
