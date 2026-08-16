package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.AllocationPlan;
import com.ses.entity.ApprovalRequest;
import com.ses.mapper.AllocationPlanMapper;
import com.ses.service.approval.ApprovalSnapshot;
import com.ses.service.approval.ApprovalTargetAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 過配賦例外（R2.2）の承認engine接続adapter。
 *
 * <p>配置の確定は {@link AllocationPlanService#confirm} が承認状態（approved）を
 * 検証してから状態CASで行う（design §5.4）。そのため本adapterのapplyApprovedは
 * 業務遷移を実行せず、承認の成立だけを承認履歴へ残す（確定はconfirmが単一経路）。
 */
@Component
@RequiredArgsConstructor
public class AllocationApprovalAdapter implements ApprovalTargetAdapter {

    public static final String REQUEST_TYPE = "staffing.overallocation";

    private final AllocationPlanMapper allocationMapper;

    @Override
    public String requestType() {
        return REQUEST_TYPE;
    }

    @Override
    public ApprovalSnapshot snapshot(Long targetId, Map<String, Object> command) {
        AllocationPlan allocation = require(targetId);
        return new ApprovalSnapshot(version(allocation), null, null,
                command == null ? Map.of() : Map.copyOf(command),
                Map.of("engineerId", allocation.getEngineerId(),
                        "allocationType", allocation.getAllocationType(),
                        "startDate", String.valueOf(allocation.getStartDate()),
                        "endDate", allocation.getEndDate() == null ? "" : String.valueOf(allocation.getEndDate()),
                        "allocationPercent", allocation.getAllocationPercent().toPlainString()));
    }

    @Override
    public long currentVersion(Long targetId) {
        return version(require(targetId));
    }

    @Override
    public void validateBeforeRequest(ApprovalSnapshot snapshot) {
        if (snapshot == null || snapshot.targetVersion() == null) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
    }

    @Override
    public void applyApproved(ApprovalRequest request) {
        // 確定はAllocationPlanServiceImpl.confirm()が承認状態を検証して状態CASで実施する。
        // ここで状態を進めると、確定時のロック付き再検証（過配賦の最新化）が迂回されるため行わない。
    }

    private AllocationPlan require(Long targetId) {
        AllocationPlan allocation = targetId == null ? null : allocationMapper.selectById(targetId);
        if (allocation == null) {
            throw BusinessException.of(404, "error.staffing.allocationNotFound");
        }
        return allocation;
    }

    private long version(AllocationPlan allocation) {
        return allocation.getVersion() == null ? 0L : allocation.getVersion().longValue();
    }
}
