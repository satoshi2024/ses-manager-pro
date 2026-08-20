package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.AllocationPlan;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.Engineer;
import com.ses.entity.ProjectPosition;
import com.ses.mapper.AllocationPlanMapper;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectPositionMapper;
import com.ses.service.approval.ApprovalEngineService;
import com.ses.service.approval.ApprovalRequestCommand;
import com.ses.service.staffing.AllocationPlanService;
import com.ses.service.staffing.StaffingClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.ses.entity.AllocationPlan.STATUS_CONFIRMED;
import static com.ses.entity.AllocationPlan.STATUS_DISCARDED;
import static com.ses.entity.AllocationPlan.STATUS_DRAFT;
import static com.ses.entity.AllocationPlan.TYPE_BENCH;
import static com.ses.entity.AllocationPlan.TYPE_INTERNAL;
import static com.ses.entity.AllocationPlan.TYPE_PROJECT;

/**
 * 要員配置計画の実装。区間代数・日単位の過配賦判定・例外承認を担当する。
 *
 * <p>競合対策（design §5.4 / S12-P1-01）: 確定はtransaction内で先に
 * {@code t_engineer} 行を FOR UPDATE でロックし（確定済み期間行が無い場合の
 * センチネル）、続けて期間行をロックして再検証する。読んでから書くまでの間に
 * 別の配置が入る競合を防ぐ。状態遷移は状態CAS（status+version条件付きUPDATE）。
 */
@Service
@RequiredArgsConstructor
public class AllocationPlanServiceImpl implements AllocationPlanService {

    private final AllocationPlanMapper allocationMapper;
    private final ApprovalRequestMapper approvalRequestMapper;
    private final EngineerMapper engineerMapper;
    private final ProjectPositionMapper positionMapper;
    private final ApprovalEngineService approvalEngineService;
    private final StaffingClock clock;

    @Override
    @Transactional
    public AllocationPlan saveDraft(AllocationPlan allocation) {
        validate(allocation);
        normalize(allocation);

        boolean over = isOverAllocated(allocation.getEngineerId(),
                allocation.getStartDate(), normalizedEnd(allocation), allocation.getAllocationPercent(),
                allocation.getId(), true);
        if (over && (allocation.getExceptionReason() == null || allocation.getExceptionReason().isBlank())) {
            throw BusinessException.of(400, "error.staffing.overAllocation");
        }
        if (allocation.getApprovalRequestId() != null && allocation.getExceptionReason() == null) {
            throw BusinessException.of(400, "error.staffing.exceptionReasonRequired");
        }

        if (allocation.getId() == null) {
            allocation.setStatus(STATUS_DRAFT);
            allocation.setVersion(0);
            allocation.setCreatedBy(SecurityUtils.currentUserId());
            allocationMapper.insert(allocation);
        } else {
            AllocationPlan existing = require(allocation.getId());
            if (!STATUS_DRAFT.equals(existing.getStatus())) {
                throw BusinessException.of(400, "error.staffing.invalidTransition",
                        existing.getStatus(), STATUS_DRAFT);
            }
            if (existing.getSourceContractId() != null) {
                throw BusinessException.of(400, "error.staffing.actualManagedByContract");
            }
            allocation.setEngineerId(existing.getEngineerId());
            allocation.setStatus(STATUS_DRAFT);
            int rows = allocationMapper.updateById(allocation);
            if (rows == 0) {
                throw BusinessException.of(409, "error.common.optimisticLock");
            }
        }

        if (over) {
            requestExceptionApproval(allocationMapper.selectById(allocation.getId()));
        }
        return allocationMapper.selectById(allocation.getId());
    }

    @Override
    @Transactional
    public AllocationPlan confirm(Long id) {
        AllocationPlan allocation = require(id);
        if (!STATUS_DRAFT.equals(allocation.getStatus())) {
            throw BusinessException.of(400, "error.staffing.invalidTransition",
                    allocation.getStatus(), STATUS_CONFIRMED);
        }
        if (allocation.getExceptionReason() != null || allocation.getApprovalRequestId() != null) {
            requireApprovedException(allocation);
        }
        // 確定済み期間行が無いと期間行FOR UPDATEだけでは直列化できないため、
        // 要員行をセンチネルとして先にロックする（S12-P1-01）。
        lockEngineerForConfirm(allocation.getEngineerId());
        // ロック付きで再検証（読んでから書くまでの競合防止。design §5.4）
        boolean over = isOverAllocated(allocation.getEngineerId(), allocation.getStartDate(),
                normalizedEnd(allocation), allocation.getAllocationPercent(), allocation.getId(), true);
        if (over && (allocation.getExceptionReason() == null || !isExceptionApproved(allocation))) {
            throw BusinessException.of(400, "error.staffing.overAllocation");
        }
        int version = value(allocation.getVersion());
        int rows = allocationMapper.update(null, new LambdaUpdateWrapper<AllocationPlan>()
                .set(AllocationPlan::getStatus, STATUS_CONFIRMED)
                .set(AllocationPlan::getVersion, version + 1)
                .set(AllocationPlan::getUpdatedAt, LocalDateTime.now())
                .eq(AllocationPlan::getId, id)
                .eq(AllocationPlan::getStatus, STATUS_DRAFT)
                .eq(AllocationPlan::getVersion, version));
        if (rows != 1) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        return allocationMapper.selectById(id);
    }

    @Override
    @Transactional
    public void discard(Long id) {
        AllocationPlan allocation = require(id);
        if (allocation.getSourceContractId() != null) {
            throw BusinessException.of(400, "error.staffing.actualManagedByContract");
        }
        if (!STATUS_DRAFT.equals(allocation.getStatus()) && !STATUS_CONFIRMED.equals(allocation.getStatus())) {
            throw BusinessException.of(400, "error.staffing.invalidTransition",
                    allocation.getStatus(), STATUS_DISCARDED);
        }
        int version = value(allocation.getVersion());
        int rows = allocationMapper.update(null, new LambdaUpdateWrapper<AllocationPlan>()
                .set(AllocationPlan::getStatus, STATUS_DISCARDED)
                .set(AllocationPlan::getVersion, version + 1)
                .set(AllocationPlan::getUpdatedAt, LocalDateTime.now())
                .eq(AllocationPlan::getId, id)
                .eq(AllocationPlan::getStatus, allocation.getStatus())
                .eq(AllocationPlan::getVersion, version));
        if (rows != 1) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
    }

    @Override
    @Transactional
    public AllocationPlan revise(Long id, AllocationPlan newAllocation) {
        AllocationPlan existing = require(id);
        if (!STATUS_CONFIRMED.equals(existing.getStatus())) {
            throw BusinessException.of(400, "error.staffing.invalidTransition",
                    existing.getStatus(), STATUS_CONFIRMED);
        }
        if (newAllocation.getEngineerId() == null) {
            newAllocation.setEngineerId(existing.getEngineerId());
        } else if (!existing.getEngineerId().equals(newAllocation.getEngineerId())) {
            throw BusinessException.of(400, "error.staffing.engineerChangedOnRevise");
        }
        validate(newAllocation);
        normalize(newAllocation);
        // 旧区間を破棄（version CAS）→ 新区間を確定。失敗時はrollbackで変更前の区間へ戻る。
        int version = value(existing.getVersion());
        int rows = allocationMapper.update(null, new LambdaUpdateWrapper<AllocationPlan>()
                .set(AllocationPlan::getStatus, STATUS_DISCARDED)
                .set(AllocationPlan::getVersion, version + 1)
                .set(AllocationPlan::getUpdatedAt, LocalDateTime.now())
                .eq(AllocationPlan::getId, id)
                .eq(AllocationPlan::getStatus, STATUS_CONFIRMED)
                .eq(AllocationPlan::getVersion, version));
        if (rows != 1) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        newAllocation.setId(null);
        newAllocation.setStatus(STATUS_DRAFT);
        newAllocation.setVersion(0);
        newAllocation.setCreatedBy(SecurityUtils.currentUserId());
        allocationMapper.insert(newAllocation);
        return confirm(newAllocation.getId());
    }

    @Override
    public AllocationPlan get(Long id) {
        return require(id);
    }

    @Override
    public List<AllocationPlan> listByEngineer(Long engineerId) {
        return allocationMapper.selectList(new LambdaQueryWrapper<AllocationPlan>()
                .eq(AllocationPlan::getEngineerId, engineerId)
                .orderByDesc(AllocationPlan::getUpdatedAt));
    }

    @Override
    public List<AllocationPlan> listByPosition(Long positionId) {
        return allocationMapper.selectList(new LambdaQueryWrapper<AllocationPlan>()
                .eq(AllocationPlan::getPositionId, positionId)
                .orderByDesc(AllocationPlan::getUpdatedAt));
    }

    // ---------------------------------------------------------------
    // 過配賦判定（日単位・design §5.2）
    // ---------------------------------------------------------------

    /**
     * 同一要員への確定を直列化するセンチネルロック（S12-P1-01）。
     * 確定済み {@code t_allocation_plan} が0件でも競合を防ぐため {@code t_engineer} をロックする。
     */
    private void lockEngineerForConfirm(Long engineerId) {
        Engineer locked = engineerMapper.selectByIdForUpdate(engineerId);
        if (locked == null) {
            throw BusinessException.of(400, "error.staffing.engineerRequired");
        }
    }

    /**
     * 指定区間の各日に、既存の確定配置（＋当該候補）の配賦率合計が100%を超える日があるか。
     * lock=trueのとき対象要員の期間行をFOR UPDATEでロックする（確定transaction内の競合防止）。
     * 区間はinclusive。open end（end_date NULL）は計画window末として扱う。
     */
    private boolean isOverAllocated(Long engineerId, LocalDate start, LocalDate end,
                                    BigDecimal percent, Long excludeId, boolean lock) {
        List<AllocationPlan> existing = allocationMapper.selectList(new LambdaQueryWrapper<AllocationPlan>()
                .eq(AllocationPlan::getEngineerId, engineerId)
                .eq(AllocationPlan::getStatus, STATUS_CONFIRMED)
                .ne(excludeId != null, AllocationPlan::getId, excludeId)
                .le(AllocationPlan::getStartDate, end)
                .and(w -> w.isNull(AllocationPlan::getEndDate)
                        .or().ge(AllocationPlan::getEndDate, start))
                .last(lock ? "FOR UPDATE" : ""));
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            BigDecimal sum = percent;
            for (AllocationPlan row : existing) {
                if (covers(row, day)) {
                    sum = sum.add(value(row.getAllocationPercent()));
                }
            }
            if (sum.compareTo(new BigDecimal("100")) > 0) {
                return true;
            }
        }
        return false;
    }

    /** 確定済み行が対象日を区間内に含むか（inclusive境界）。 */
    private boolean covers(AllocationPlan row, LocalDate day) {
        if (row.getStartDate() == null || day.isBefore(row.getStartDate())) {
            return false;
        }
        return row.getEndDate() == null || !day.isAfter(row.getEndDate());
    }

    // ---------------------------------------------------------------
    // 例外承認（R2.2・design §5.2）
    // ---------------------------------------------------------------

    private void requestExceptionApproval(AllocationPlan allocation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("engineerId", allocation.getEngineerId());
        payload.put("allocationType", allocation.getAllocationType());
        payload.put("startDate", String.valueOf(allocation.getStartDate()));
        payload.put("endDate", allocation.getEndDate() == null ? "" : String.valueOf(allocation.getEndDate()));
        payload.put("allocationPercent", allocation.getAllocationPercent().toPlainString());
        payload.put("exceptionReason",
                allocation.getExceptionReason() == null ? "" : allocation.getExceptionReason());
        ApprovalRequest approval = approvalEngineService.request(new ApprovalRequestCommand(
                AllocationApprovalAdapter.REQUEST_TYPE,
                "ALLOCATION_PLAN", allocation.getId(), (long) value(allocation.getVersion()),
                SecurityUtils.currentUserId(), null, null,
                payload,
                Map.of("beforeStatus", STATUS_DRAFT, "afterStatus", STATUS_CONFIRMED),
                "staffing-overalloc:" + allocation.getId() + ":" + value(allocation.getVersion())));
        if (approval != null) {
            allocationMapper.update(null, new LambdaUpdateWrapper<AllocationPlan>()
                    .set(AllocationPlan::getApprovalRequestId, approval.getId())
                    .eq(AllocationPlan::getId, allocation.getId()));
        }
    }

    private void requireApprovedException(AllocationPlan allocation) {
        if (allocation.getExceptionReason() == null || allocation.getExceptionReason().isBlank()) {
            throw BusinessException.of(400, "error.staffing.exceptionReasonRequired");
        }
        if (allocation.getApprovalRequestId() == null) {
            throw BusinessException.of(400, "error.staffing.exceptionApprovalRequired");
        }
        if (!isExceptionApproved(allocation)) {
            throw BusinessException.of(400, "error.staffing.exceptionNotApproved");
        }
    }

    private boolean isExceptionApproved(AllocationPlan allocation) {
        if (allocation.getApprovalRequestId() == null) {
            return false;
        }
        ApprovalRequest approval = approvalRequestMapper.selectById(allocation.getApprovalRequestId());
        return approval != null && "approved".equals(approval.getStatus());
    }

    // ---------------------------------------------------------------
    // 検証・正規化
    // ---------------------------------------------------------------

    private void validate(AllocationPlan allocation) {
        if (allocation.getEngineerId() == null || engineerMapper.selectById(allocation.getEngineerId()) == null) {
            throw BusinessException.of(400, "error.staffing.engineerRequired");
        }
        if (allocation.getStartDate() == null) {
            throw BusinessException.of(400, "error.staffing.startDateRequired");
        }
        if (TYPE_PROJECT.equals(allocation.getAllocationType())) {
            if (allocation.getPositionId() == null) {
                throw BusinessException.of(400, "error.staffing.positionRequired");
            }
            if (positionMapper.selectById(allocation.getPositionId()) == null) {
                throw BusinessException.of(404, "error.staffing.positionNotFound");
            }
        } else if (TYPE_INTERNAL.equals(allocation.getAllocationType())
                || TYPE_BENCH.equals(allocation.getAllocationType())) {
            if (allocation.getPositionId() != null) {
                throw BusinessException.of(400, "error.staffing.noPositionForInternal");
            }
        } else {
            throw BusinessException.of(400, "error.staffing.invalidAllocationType");
        }
    }

    private void normalize(AllocationPlan allocation) {
        LocalDate horizonEnd = clock.horizonEnd();
        if (allocation.getStartDate().isAfter(horizonEnd)) {
            throw BusinessException.of(400, "error.staffing.horizonExceeded");
        }
        if (allocation.getEndDate() != null) {
            if (allocation.getEndDate().isBefore(allocation.getStartDate())) {
                throw BusinessException.of(400, "error.staffing.invalidPeriod");
            }
            if (allocation.getEndDate().isAfter(horizonEnd)) {
                throw BusinessException.of(400, "error.staffing.horizonExceeded");
            }
        }
    }

    /** open end（end_date NULL）を計画window末へ正規化（design §5.2）。 */
    private LocalDate normalizedEnd(AllocationPlan allocation) {
        return allocation.getEndDate() == null ? clock.horizonEnd() : allocation.getEndDate();
    }

    private AllocationPlan require(Long id) {
        AllocationPlan allocation = id == null ? null : allocationMapper.selectById(id);
        if (allocation == null) {
            throw BusinessException.of(404, "error.staffing.allocationNotFound");
        }
        return allocation;
    }

    private int value(Integer version) {
        return version == null ? 0 : version;
    }

    private BigDecimal value(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
