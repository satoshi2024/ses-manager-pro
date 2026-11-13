package com.ses.service.expense;

import com.ses.entity.EngineerAccountLink;
import com.ses.entity.ExpenseRequest;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.service.NotificationService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 経費の会計連携完了を申請者本人へ通知する（EXPENSE_ACCOUNTING_SENT）。
 *
 * <p>経費の会計連携には、承認後に自動で送る経路（{@link ExpenseAccountingJobScheduler}）と、
 * 会計・支払連携画面から手動で送る経路（EXPENSE_DEAL_SYNC）がある。どちらで送っても通知は
 * 1件だけになるよう、dedupeKeyは経費IDだけで決める。会計側で取引が作成されたことを確認した
 * 経路からだけ呼ぶこと（未送信のまま「連携しました」と通知しない）。</p>
 */
@Component
@RequiredArgsConstructor
public class ExpenseAccountingNotifier {

    static final String TYPE = "EXPENSE_ACCOUNTING_SENT";
    static final String LINK_MENU_KEY = "myExpenses";
    static final String LINK_URL = "/my/expenses";

    private final EngineerAccountLinkMapper engineerAccountLinkMapper;
    private final NotificationService notificationService;

    /** 申請者本人へ会計連携通知を発行する（dedupeKeyで冪等）。要員アカウント未連携なら何もしない。 */
    public void notifySent(ExpenseRequest expense) {
        Long userId = applicantUserId(expense);
        if (userId == null) {
            return;
        }
        String message = "[\"notification.msg.EXPENSE_ACCOUNTING_SENT\", \"" + displayExpenseNo(expense) + "\"]";
        notificationService.publishToUser(userId, TYPE, "経費を会計へ連携しました",
                message, LINK_URL, dedupeKey(expense.getId()), LINK_MENU_KEY);
    }

    Long applicantUserId(ExpenseRequest expense) {
        if (expense == null || expense.getEngineerId() == null) {
            return null;
        }
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        EngineerAccountLink link = engineerAccountLinkMapper.selectByEngineerIdAndTenant(
                expense.getEngineerId(), tenantId);
        return link == null ? null : link.getSysUserId();
    }

    static String dedupeKey(Long expenseId) {
        return "expense-accounting-sent:" + expenseId;
    }

    static String displayExpenseNo(ExpenseRequest expense) {
        return expense.getExpenseNo() == null || expense.getExpenseNo().isBlank()
                ? "EX-" + expense.getId() : expense.getExpenseNo();
    }
}
