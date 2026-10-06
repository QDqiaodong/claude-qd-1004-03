package com.citygas.network;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安全巡检批次列表：筛选 / 分页 / 多租户边界接口测试（MockMvc + H2）。
 *
 * <p>本类锁定本次修复的核心约束：
 * <ul>
 *   <li>关键字（批次编号 / 负责人）只在当前租户内匹配，其它租户同编号批次绝不混入；</li>
 *   <li>状态、关键字、只看过期未执行三个条件始终取交集，勾选「只看过期未执行」
 *       不得丢掉状态条件（回归早期二选一查询的缺陷）；</li>
 *   <li>翻页（page）、改每页条数（size）只改分页不改筛选，页码 0 基、页大小上限 100、越界夹紧；</li>
 *   <li>空结果返回结构完整的空页；空白筛选等价于不筛选；非法状态 / 非法参数走统一错误结构。</li>
 * </ul>
 * 批次通过 JDBC 按租户直接写入，绕开创建接口以精确控制状态、负责人和计划时间。</p>
 */
@AutoConfigureMockMvc
class BatchSearchWebTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String TENANT_A = "A片区";
    private static final String TENANT_B = "B片区";

    private Long insertBatch(String tenant, String no, String owner, String status,
                             LocalDateTime planStartAt) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                        insert into inspect_batch
                        (tenant_id, batch_no, plan_start_at, plan_end_at, owner_name, remark,
                         status, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                tenant, no, planStartAt, null, owner, "筛选测试批次-" + no, status, now, now);
        return jdbc.queryForObject(
                "select id from inspect_batch where tenant_id = ? and batch_no = ?",
                Long.class, tenant, no);
    }

    private ResultActions list(String tenant, Object... kv) throws Exception {
        var builder = get("/api/inspection-batches");
        for (int i = 0; i < kv.length; i += 2) {
            builder.param((String) kv[i], String.valueOf(kv[i + 1]));
        }
        return mockMvc.perform(builder.header("X-Tenant", tenant));
    }

    private List<String> batchNos(MvcResult r) throws Exception {
        JsonNode items = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(r.getResponse().getContentAsString()).path("data").path("items");
        List<String> nos = new ArrayList<>();
        items.forEach(item -> nos.add(item.path("batchNo").asText()));
        return nos;
    }

    // ==================== 多租户边界 ====================

    @Test
    void keyword_should_match_only_within_current_tenant() throws Exception {
        // 两租户各有一个编号完全相同的批次：关键字只能命中当前租户那条
        Long idA = insertBatch(TENANT_A, "XJ-SAME", "张工", "待执行", LocalDateTime.now().plusDays(1));
        insertBatch(TENANT_B, "XJ-SAME", "张工", "待执行", LocalDateTime.now().plusDays(1));

        list(TENANT_A, "keyword", "XJ-SAME")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(idA))
                .andExpect(jsonPath("$.data.items[0].tenantId").value(TENANT_A));

        // 按负责人关键字同样只出本租户
        list(TENANT_B, "keyword", "张工")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].batchNo").value("XJ-SAME"))
                .andExpect(jsonPath("$.data.items[0].tenantId").value(TENANT_B));

        // 关键字在另一租户存在、当前租户不存在 → 空结果，不允许把别的租户带出来
        insertBatch(TENANT_A, "XJ-ONLY-A", "李工", "待执行", LocalDateTime.now().plusDays(1));
        list(TENANT_B, "keyword", "ONLY-A")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void list_without_filter_should_never_mix_other_tenant_rows() throws Exception {
        insertBatch(TENANT_A, "XJ-A1", "张工", "待执行", LocalDateTime.now().plusDays(1));
        insertBatch(TENANT_A, "XJ-A2", "张工", "执行中", LocalDateTime.now().minusDays(2));
        insertBatch(TENANT_B, "XJ-B1", "王工", "待执行", LocalDateTime.now().plusDays(1));

        list(TENANT_A)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));
        list(TENANT_B)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].batchNo").value("XJ-B1"));
    }

    // ==================== 组合筛选（含回归：只看过期未执行不得吞掉状态条件） ====================

    @Test
    void status_keyword_and_overdue_filters_should_compose_as_intersection() throws Exception {
        insertBatch(TENANT_A, "XJ-PEND-FUTURE", "张工", "待执行", LocalDateTime.now().plusDays(1));
        insertBatch(TENANT_A, "XJ-PEND-OVERDUE", "张工", "待执行", LocalDateTime.now().minusHours(2));
        insertBatch(TENANT_A, "XJ-RUN-OVERDUE", "张工", "执行中", LocalDateTime.now().minusDays(3));
        insertBatch(TENANT_A, "XJ-CLOSED-OLD", "赵工", "已关闭", LocalDateTime.now().minusDays(30));

        // 状态 + 关键字交集：只匹配编号含 PEND 的待执行批次
        list(TENANT_A, "status", "待执行", "keyword", "PEND")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));

        // 只看过期未执行 + 关键字：仅命中「过期且待执行」的那条，执行中/已关闭不得混入
        list(TENANT_A, "overdueOnly", "true", "keyword", "XJ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].batchNo").value("XJ-PEND-OVERDUE"));

        // 回归核心：状态=执行中 且 只看过期未执行，条件互斥必须为空页，
        // 而不是退化成「只看执行中」或「只看过期」
        list(TENANT_A, "status", "执行中", "overdueOnly", "true")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());

        // 状态=待执行 且 只看过期未执行：两者交集正常工作
        list(TENANT_A, "status", "待执行", "overdueOnly", "true")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].batchNo").value("XJ-PEND-OVERDUE"));
    }

    @Test
    void owner_keyword_should_match_owner_name() throws Exception {
        insertBatch(TENANT_A, "XJ-OWN-1", "责任人陈晨", "待执行", LocalDateTime.now().plusDays(1));
        insertBatch(TENANT_A, "XJ-OWN-2", "张工", "待执行", LocalDateTime.now().plusDays(1));

        list(TENANT_A, "keyword", "陈晨")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].batchNo").value("XJ-OWN-1"));
    }

    // ==================== 翻页 / 每页条数 ====================

    @Test
    void paging_should_keep_filter_and_recalculate_from_first_page_semantics() throws Exception {
        // 3 条本租户匹配 + 1 条它租户噪声 + 1 条本租户不匹配噪声
        insertBatch(TENANT_A, "XJ-F1", "张工", "待执行", LocalDateTime.now().plusDays(1));
        insertBatch(TENANT_A, "XJ-F2", "张工", "待执行", LocalDateTime.now().plusDays(1));
        insertBatch(TENANT_A, "XJ-F3", "张工", "待执行", LocalDateTime.now().plusDays(1));
        insertBatch(TENANT_B, "XJ-F1", "张工", "待执行", LocalDateTime.now().plusDays(1));
        insertBatch(TENANT_A, "XJ-OTHER", "李四", "执行中", LocalDateTime.now().plusDays(1));

        // size=2：total 只统计本租户匹配集（3），第 1 页 2 条
        MvcResult first = list(TENANT_A, "keyword", "XJ-F", "size", "2", "page", "0")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(2))
                .andReturn();
        assertThatFirstPage(first);

        // 第 2 页：筛选条件原样保留，只剩 1 条，且与第 1 页不重复、无其它租户
        MvcResult second = list(TENANT_A, "keyword", "XJ-F", "size", "2", "page", "1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andReturn();
        List<String> page1 = batchNos(first);
        List<String> page2 = batchNos(second);
        org.assertj.core.api.Assertions.assertThat(page2).doesNotContainAnyElementsOf(page1);
        org.assertj.core.api.Assertions.assertThat(page2).containsExactly("XJ-F1");

        // 改每页条数后筛选仍在，结果集重新分页：size=5 一页装下 3 条
        list(TENANT_A, "keyword", "XJ-F", "size", "5", "page", "0")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items.length()").value(3));

        // 页码越过末页：空页但 total/totalPages 仍是筛选集的真实值
        list(TENANT_A, "keyword", "XJ-F", "size", "2", "page", "9")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items").isEmpty());

        // size 超上限夹紧到 100；负 page / size 夹紧，不报 500
        list(TENANT_A, "size", "999", "page", "0")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));
        list(TENANT_A, "size", "-5", "page", "-3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(1));
    }

    private void assertThatFirstPage(MvcResult first) throws Exception {
        // id desc 排序：后插入的在前
        org.assertj.core.api.Assertions.assertThat(batchNos(first))
                .containsExactly("XJ-F3", "XJ-F2");
    }

    // ==================== 空结果 / 空白筛选 / 非法参数 ====================

    @Test
    void empty_result_should_return_well_formed_empty_page() throws Exception {
        list(TENANT_A, "keyword", "不存在的编号XYZ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(10));
    }

    @Test
    void blank_filters_should_be_treated_as_no_filter() throws Exception {
        insertBatch(TENANT_A, "XJ-B1", "张工", "待执行", LocalDateTime.now().plusDays(1));

        list(TENANT_A, "status", "  ", "keyword", "   ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].batchNo").value("XJ-B1"));
    }

    @Test
    void illegal_status_should_return_unified_error() throws Exception {
        list(TENANT_A, "status", "已报废")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("批次状态不合法")));
    }

    @Test
    void non_numeric_page_and_size_should_return_unified_400_not_500() throws Exception {
        list(TENANT_A, "page", "abc")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("请求参数类型不正确")));

        list(TENANT_A, "size", "xx")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));

        list(TENANT_A, "overdueOnly", "maybe")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));
    }

    // ==================== 详情 / 创建不回归 ====================

    @Test
    void detail_and_create_flow_should_still_work_after_search_fix() throws Exception {
        // 创建（走完整创建接口）后立刻能被筛选列表与详情读到
        MvcResult created = mockMvc.perform(post("/api/inspection-batches")
                        .header("X-Tenant", TENANT_A)
                        .contentType("application/json")
                        .content("""
                                {"batchNo":"XJ-CREATE-1","planStartAt":"%s","ownerName":"周工","remark":"创建不回归",
                                 "targets":[{"targetType":"PIPE_SEGMENT","targetId":%d}]}
                                """.formatted(LocalDateTime.now().plusDays(1), newPipeSegment(TENANT_A, "PS-S01"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.batchNo").value("XJ-CREATE-1"))
                .andReturn();
        long batchId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        list(TENANT_A, "keyword", "CREATE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(batchId));

        mockMvc.perform(get("/api/inspection-batches/" + batchId).header("X-Tenant", TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.batch.batchNo").value("XJ-CREATE-1"))
                .andExpect(jsonPath("$.data.openHazardCount").value(0));

        // 详情跨租户仍不可见
        mockMvc.perform(get("/api/inspection-batches/" + batchId).header("X-Tenant", TENANT_B))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("批次不存在")));
    }

    private Long newPipeSegment(String tenant, String code) {
        jdbc.update("""
                        insert into pipe_segment
                        (tenant_id, seg_code, start_point, end_point, material,
                         pressure_level, length_m, status)
                        values (?, ?, 'A', 'B', '钢管', '中压', 100, '正常')""",
                tenant, code);
        return jdbc.queryForObject(
                "select id from pipe_segment where tenant_id = ? and seg_code = ?",
                Long.class, tenant, code);
    }
}
