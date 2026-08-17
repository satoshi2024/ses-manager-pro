package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.Engineer;
import com.ses.entity.ExpenseRequest;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ExpenseRequestMapper;
import com.ses.service.approval.ApprovalSnapshot;
import com.ses.service.approval.ApprovalTargetAdapter;
import com.ses.service.expense.ExpenseRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 経費申請を既存approval engineへ接続するadapter（T091 / design §6.3）。
 * requestType=expense.request、targetType=EXPENSE_REQUEST。
 * 金額なしrouteはamountBand無しで解決される（RouteResolverServiceImpl）。
 * 最終承認時だけ状態CAS（申請中→承認済）と楽観ロックversion検証を実行する。
 * 既に会計連携済の経費への適用はno-opでなくエラーにする（二重連携のfail-closed）。
 */
@Component
@RequiredArgsConstructor
public class ExpenseRequestApprovalAdapter implements ApprovalTargetAdapter {

    public static final String REQUEST_TYPE = "expense.request";

    private static final String APPLIED = ExpenseRequestService.STATUS_APPLIED;

    private final ExpenseRequestMapper expenseRequestMapper;
    private final EngineerMapper engineerMapper;

    @Override
    public String requestType() {
        return REQUEST_TYPE;
    }

    @Override
    public ApprovalSnapshot snapshot(Long targetId, Map<String, Object> command) {
        ExpenseRequest expense = require(targetId);
        if (!ExpenseRequestService.STATUS_DRAFT.equals(expense.getStatus())
                && !APPLIED.equals(expense.getStatus())) {
            throw BusinessException.of(400, "error.expense.invalidTransition",
                    expense.getStatus(), APPLIED);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("expenseNo", expense.getExpenseNo());
        payload.put("category", expense.getCategory());
        payload.put("amount", expense.getAmount());
        payload.put("expenseDate", expense.getExpenseDate() == null ? null : expense.getExpenseDate().toString());
        payload.put("customerId", expense.getCustomerId());
        payload.put("projectId", expense.getProjectId());
        payload.put("description", expense.getDescription());
        payload.put("receiptDocumentId", expense.getReceiptDocumentId());
        Map<String, Object> diff = Map.of(
                "beforeStatus", expense.getStatus(),
                "afterStatus", ExpenseRequestService.STATUS_APPROVED);
        return new ApprovalSnapshot(version(expense), expense.getAmount(), organizationId(expense),
                payload, diff);
    }

    @Override
    public long currentVersion(Long targetId) {
        return version(require(targetId));
    }

    @Override
    public void validateBeforeRequest(ApprovalSnapshot snapshot) {
        if (snapshot == null || snapshot.targetVersion() == null) {
            throw BusinessException.of(409, "error.attendance.concurrent");
        }
    }

    @Override
    public void applyApproved(ApprovalRequest request) {
        if (request == null || request.getTargetId() == null || request.getTargetVersion() == null) {
            throw BusinessException.of(409, "error.attendance.concurrent");
        }
        ExpenseRequest expense = require(request.getTargetId());
        requireVersion(request, expense);
        if (!APPLIED.equals(expense.getStatus())) {
            // 既に会計連携済・支払済の経費への適用はno-opでなくエラー（二重連携のfail-closed）。
            throw BusinessException.of(400, "error.expense.invalidTransition",
                    expense.getStatus(), ExpenseRequestService.STATUS_APPROVED);
        }
        int version = expense.getVersion() == null ? 0 : expense.getVersion();
        int updated = expenseRequestMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ExpenseRequest>()
                .eq("id", expense.getId())
                .eq("status", APPLIED)
                .eq("version", version)
                .set("status", ExpenseRequestService.STATUS_APPROVED)
                .set("version", version + 1)
                .set("updated_at", java.time.LocalDateTime.now()));
        if (updated != 1) {
            throw BusinessException.of(409, "error.attendance.concurrent");
        }
    }

    private ExpenseRequest require(Long targetId) {
        ExpenseRequest expense = targetId == null ? null : expenseRequestMapper.selectById(targetId);
        if (expense == null) {
            throw BusinessException.of(404, "error.expense.notFound");
        }
        return expense;
    }

    private void requireVersion(ApprovalRequest request, ExpenseRequest expense) {
        if (!java.util.Objects.equals(request.getTargetVersion(), version(expense))) {
            throw BusinessException.of(409, "error.attendance.concurrent");
        }
    }

    private long version(ExpenseRequest expense) {
        return expense.getVersion() == null ? 0L : expense.getVersion().longValue();
    }

    /** 経費の所属要員の組織ID（route解決用。所属不明はnull）。 */
    private Long organizationId(ExpenseRequest expense) {
        if (expense.getEngineerId() == null) {
            return null;
        }
        Engineer engineer = engineerMapper.selectById(expense.getEngineerId());
        return engineer == null ? null : engineer.getOrganizationId();
    }
}
