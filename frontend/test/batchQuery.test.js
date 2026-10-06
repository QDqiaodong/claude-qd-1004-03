import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildListParams, clampSize, sameFilter } from '../src/utils/batchQuery.js'

// 查询参数装配：后端 page 为 0 基、前端页码为 1 基；空筛选缺省；只看过期未执行显式带上。
test('buildListParams maps 1-based page to 0-based and keeps size', () => {
  const p = buildListParams({ status: '', keyword: '', overdueOnly: false, page: 3, size: 20 })
  assert.equal(p.page, 2)
  assert.equal(p.size, 20)
  assert.deepEqual(Object.keys(p).sort(), ['page', 'size'])
})

test('buildListParams keeps all filled filters together (regression: keyword was read from query.key)', () => {
  const p = buildListParams({
    status: '待执行', keyword: 'XJ-001', overdueOnly: true, page: 1, size: 10
  })
  assert.equal(p.status, '待执行')
  assert.equal(p.keyword, 'XJ-001')
  assert.equal(p.overdueOnly, true)
  assert.equal(p.page, 0)
})

test('blank / whitespace filters are omitted so backend treats them as no filter', () => {
  const p = buildListParams({ status: '   ', keyword: '  ', overdueOnly: false, page: 2, size: 50 })
  assert.equal(p.status, undefined)
  assert.equal(p.keyword, undefined)
  assert.equal(p.overdueOnly, undefined)
  assert.equal(p.page, 1)
})

test('negative and non-finite pages clamp to 0 on the wire; sizes clamp to [1,100]', () => {
  assert.equal(buildListParams({ page: -9, size: 10 }).page, 0)
  assert.equal(buildListParams({ page: NaN, size: 10 }).page, 0)
  assert.equal(clampSize(-5), 1)
  assert.equal(clampSize(999), 100)
  assert.equal(clampSize('abc'), 10)
})

test('sameFilter compares only filter fields, ignoring page/size', () => {
  assert.equal(sameFilter(
    { status: '执行中', keyword: '张', overdueOnly: false, page: 1, size: 10 },
    { status: '执行中', keyword: '张', overdueOnly: false, page: 4, size: 50 }
  ), true)
  assert.equal(sameFilter(
    { status: '执行中', keyword: '张', overdueOnly: false },
    { status: '执行中', keyword: '张', overdueOnly: true }
  ), false)
  assert.equal(sameFilter({ status: '', keyword: '' }, { status: null, keyword: null }), true)
})
