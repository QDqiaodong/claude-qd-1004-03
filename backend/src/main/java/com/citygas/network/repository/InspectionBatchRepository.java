package com.citygas.network.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.citygas.network.entity.InspectionBatch;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

/** 巡检批次 Repository：租户过滤由实体上的 @TenantId 自动完成 */
public interface InspectionBatchRepository extends JpaRepository<InspectionBatch, Long> {

    Optional<InspectionBatch> findByBatchNo(String batchNo);

    boolean existsByBatchNo(String batchNo);

    /** 显式 JPQL 按主键读取，确保叠加 @TenantId 租户谓词（默认 findById 不叠加） */
    @Query("select b from InspectionBatch b where b.id = :id")
    Optional<InspectionBatch> findScopedById(@Param("id") Long id);

    /**
     * 分页筛选：状态 + 编号/负责人模糊关键字 + 只看过期未执行，三个条件同时生效、组合取交集；
     * 条件为 null / false 时不参与过滤。租户谓词由 Hibernate {@code @TenantId} 自动追加，
     * 任何情况下都只返回当前租户的批次。
     *
     * <p>关键：overdueOnly 与 status 必须在同一条 SQL 内做 AND——
     * 早期实现按 overdueOnly 二选一走不同查询，勾选「只看过期未执行」时会丢掉前端的状态条件，
     * 把非「待执行」状态的批次也带进列表，造成筛选/分页状态不一致。
     * 当 overdueOnly=true 且 status 指向其它状态时，两个条件互斥，SQL 自然返回空页。</p>
     */
    @Query("""
            select b from InspectionBatch b
            where (:status is null or b.status = :status)
              and (:overdueOnly = false
                   or (b.status = '待执行' and b.planStartAt < :now))
              and (:keyword is null
                   or b.batchNo like concat('%', :keyword, '%')
                   or b.ownerName like concat('%', :keyword, '%'))
            order by b.id desc
            """)
    Page<InspectionBatch> search(@Param("status") String status,
                                 @Param("keyword") String keyword,
                                 @Param("overdueOnly") boolean overdueOnly,
                                 @Param("now") java.time.LocalDateTime now,
                                 Pageable pageable);

    /**
     * 关闭收口用的行级悲观锁：两名操作员并发关闭时，后到者在拿锁阶段即被串行化，
     * 拿到锁后在事务内复查状态——批次已被对方关闭则抛冲突，绝不覆盖先到者结果。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select b from InspectionBatch b where b.id = :id")
    Optional<InspectionBatch> findByIdForUpdate(@Param("id") Long id);
}
