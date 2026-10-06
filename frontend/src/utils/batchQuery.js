// 安全巡检批次列表的查询参数装配。
//
// 核心约束（本次修复）：任何翻页 / 改每页条数 / 切换状态 / 勾选「只看过期未执行」的动作，
// 发往后端的筛选条件（keyword/status/overdueOnly）都必须从同一个 query 状态里读取，
// 绝不允许在某个调用路径上漏传或读到旧字段名，导致把上一轮或其它租户的结果混进列表。
//
// 前端页码是 1 基（el-pagination），后端 page 是 0 基；只在这里做一次减法。

export const DEFAULT_PAGE_SIZE = 10
export const MAX_PAGE_SIZE = 100

/** 去掉首尾空白；空串归一为 null（后端把 null / 空白都解释为「不筛选」）。 */
function normalizeText(v) {
  if (v === null || v === undefined) return null
  const t = String(v).trim()
  return t === '' ? null : t
}

/**
 * 由列表查询状态装配后端查询参数。
 * @param {{status?:string, keyword?:string, overdueOnly?:boolean, page:number, size:number}} query
 *        page 为 1 基页码
 * @returns {{status?:string, keyword?:string, overdueOnly?:boolean, page:number, size:number}}
 *          page 已换算为 0 基；值为空的筛选字段直接缺省，不发给后端
 */
export function buildListParams(query) {
  const status = normalizeText(query.status)
  const keyword = normalizeText(query.keyword)
  const size = clampSize(query.size)
  return {
    ...(status ? { status } : {}),
    ...(keyword ? { keyword } : {}),
    ...(query.overdueOnly ? { overdueOnly: true } : {}),
    page: Math.max(0, (Number.isFinite(query.page) ? Math.floor(query.page) : 1) - 1),
    size
  }
}

/** 每页条数夹紧到 [1, 100]，与后端 InspectionService.search 的夹紧规则一致。 */
export function clampSize(size) {
  const n = Number(size)
  if (!Number.isFinite(n)) return DEFAULT_PAGE_SIZE
  return Math.min(Math.max(Math.floor(n), 1), MAX_PAGE_SIZE)
}

/** 两个筛选条件（不含页码/页大小）是否一致——用于重复在途请求去重。 */
export function sameFilter(a, b) {
  return normalizeText(a?.status) === normalizeText(b?.status)
    && normalizeText(a?.keyword) === normalizeText(b?.keyword)
    && Boolean(a?.overdueOnly) === Boolean(b?.overdueOnly)
}
