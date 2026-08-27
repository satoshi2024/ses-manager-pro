package com.ses.service.expense;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.ExpenseAccountingJob;
import com.ses.entity.ExpenseRequest;
import com.ses.mapper.ExpenseAccountingJobMapper;
import com.ses.mapper.ExpenseRequestMapper;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.NotificationService;
import com.ses.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 経費の会計連携outbox scheduler（T091 / design §6.3）。
 * 承認済かつ未連携の経費へjobを作成（UNIQUE(expense_request_id)で冪等）し、
 * REQUIRES_NEW claim → DB transaction外のsend → 結果反映（状態CAS）を行う。
 * 成功時: expense.status=会計連携済 + accounting_job_id記録 + 本人へEXPENSE_ACCOUNTING_SENT通知。
 * 失敗時: attempt加算 + backoff再試行、max 5回でFAILED。
 * 二重起動・クラッシュ後リトライでも同一経費の二重連携は起きない。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpenseAccountingJobScheduler {

    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_PROCESSING = "PROCESSING";
    static final String STATUS_SUCCEEDED = "SUCCEEDED";
    static final String STATUS_FAILED = "FAILED";
    static final int MAX_ATTEMPTS = 5;
    static final String LINK_MENU_KEY = "myExpenses";
    static final String LINK_URL = "/my/expenses";

    private final ExpenseRequestMapper expenseRequestMapper;
    private final ExpenseAccountingJobMapper jobMapper;
    private final ObjectProvider<ExpenseAccountingSender> senderProvider;
    private final SystemConfigService systemConfigService;
    private final EngineerAccountLinkService engineerAccountLinkService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final PlatformTransactionManager transactionManager;

    private TransactionTemplate requiresNewTx() {
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tt;
    }

    @Scheduled(cron = "0 * * * * *")
    @SchedulerLock(name = "expenseAccountingDispatch", lockAtLeastFor = "PT10S", lockAtMostFor = "PT5M")
    public void dispatchPending() {
        processDue(100);
    }

    /** schedulerと同じ経路をテスト/Demoから起動する。処理したjob件数を返す。 */
    public int processDue(int limit) {
        recoverStaleRows();
        createAccountingJobs(limit);
        int processed = 0;
        for (Long expenseRequestId : dueJobExpenseRequestIds(limit)) {
            if (dispatchOne(expenseRequestId)) {
                processed++;
            }
        }
        return processed;
    }

    /** 30分以上claimされたままの行を再送可能へ戻す（クラッシュ耐性）。 */
    public void recoverStaleRows() {
        requiresNewTx().executeWithoutResult(st -> {
            LocalDateTime now = LocalDateTime.now(clock);
            jobMapper.update(null, new UpdateWrapper<ExpenseAccountingJob>()
                    .eq("status", STATUS_PROCESSING)
                    .lt("updated_at", now.minusMinutes(30))
                    .set("status", STATUS_PENDING)
                    .set("next_attempt_at", now));
        });
    }

    /** 承認済かつ未連携の経費へPENDING jobを作成する（UNIQUE(expense_request_id)衝突は冪等スキップ）。 */
    @Transactional(rollbackFor = Exception.class)
    public int createAccountingJobs(int limit) {
        List<ExpenseRequest> approved = expenseRequestMapper.selectList(new LambdaQueryWrapper<ExpenseRequest>()
                .eq(ExpenseRequest::getStatus, ExpenseRequestService.STATUS_APPROVED)
                .isNull(ExpenseRequest::getAccountingJobId)
                .orderByAsc(ExpenseRequest::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 1000)));
        int created = 0;
        LocalDateTime now = LocalDateTime.now(clock);
        for (ExpenseRequest expense : approved) {
            ExpenseAccountingJob job = ExpenseAccountingJob.builder()
                    .expenseRequestId(expense.getId())
                    .status(STATUS_PENDING)
                    .payloadHash(payloadHash(expense))
                    .attemptCount(0)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            try {
                jobMapper.insert(job);
                created++;
            } catch (DuplicateKeyException e) {
                // 既にjobが存在するためスキップ（冪等）。
            }
        }
        if (created > 0) {
            log.info("[経費会計連携] PENDING jobを作成しました: count={}", created);
        }
        return created;
    }

    List<Long> dueJobExpenseRequestIds(int limit) {
        LocalDateTime now = LocalDateTime.now(clock);
        return jobMapper.selectList(new LambdaQueryWrapper<ExpenseAccountingJob>()
                        .eq(ExpenseAccountingJob::getStatus, STATUS_PENDING)
                        .and(w -> w.isNull(ExpenseAccountingJob::getNextAttemptAt)
                                .or().le(ExpenseAccountingJob::getNextAttemptAt, now))
                        .orderByAsc(ExpenseAccountingJob::getExpenseRequestId)
                        .last("LIMIT " + Math.min(Math.max(limit, 1), 1000)))
                .stream()
                .map(ExpenseAccountingJob::getExpenseRequestId)
                .toList();
    }

    /**
     * 1件の連携を処理する。claim(REQUIRES_NEW) → transaction外send → 結果反映(REQUIRES_NEW)。
     *
     * @return sendが成功したかどうか
     */
    public boolean dispatchOne(Long expenseRequestId) {
        ClaimedJob claimed = claim(expenseRequestId);
        if (claimed == null) {
            return false;
        }
        ExpenseAccountingSender sender = resolveSender();
        ExpenseAccountingSender.SendResult result;
        if (sender == null) {
            log.warn("[経費会計連携] provider未設定のため連携できません: expenseId={}", expenseRequestId);
            result = new ExpenseAccountingSender.SendResult(false, null, "PROVIDER_UNAVAILABLE");
        } else {
            try {
                result = sender.send(claimed.expense(), claimed.job());
            } catch (RuntimeException e) {
                log.warn("[経費会計連携] send例外: expenseId={} error={}", expenseRequestId, e.getMessage());
                result = new ExpenseAccountingSender.SendResult(false, null, "SEND_ERROR");
            }
        }
        if (result.success()) {
            try {
                markSent(expenseRequestId, claimed, result.correlationId());
                log.info("[経費会計連携] 会計連携済: expenseId={} correlationId={}", claimed.expense().getId(), result.correlationId());
            } catch (RuntimeException e) {
                log.error("[経費会計連携] markSentコミット失敗（再試行可能へ戻します）: expenseId={}", expenseRequestId, e);
                markFailure(expenseRequestId, claimed, "DB_COMMIT_FAILED");
                return false;
            }
        } else {
            markFailure(expenseRequestId, claimed,
                    result.errorCode() == null ? "SEND_FAILED" : result.errorCode());
        }
        return result.success();
    }

    /** m_system_configのexpense.accounting.provider（既定mock）に一致するsenderを選ぶ。 */
    private ExpenseAccountingSender resolveSender() {
        String provider = systemConfigService.getString("expense.accounting.provider", "mock");
        if (provider == null || provider.isBlank()) {
            return null;
        }
        return senderProvider.stream()
                .filter(s -> provider.equals(s.providerName()))
                .findFirst()
                .orElse(null);
    }

    /** PENDINGかつ再試行期限到来のjobをPROCESSINGへclaimし、対象経費と合わせて返す。 */
    public ClaimedJob claim(Long expenseRequestId) {
        return requiresNewTx().execute(st -> {
            LocalDateTime now = LocalDateTime.now(clock);
            int updated = jobMapper.update(null, new UpdateWrapper<ExpenseAccountingJob>()
                    .eq("expense_request_id", expenseRequestId)
                    .eq("status", STATUS_PENDING)
                    .and(w -> w.isNull("next_attempt_at").or().le("next_attempt_at", now))
                    .set("status", STATUS_PROCESSING)
                    .setSql("attempt_count = attempt_count + 1")
                    .set("updated_at", now));
            if (updated != 1) {
                return null;
            }
            ExpenseAccountingJob job = jobMapper.selectOne(new LambdaQueryWrapper<ExpenseAccountingJob>()
                    .eq(ExpenseAccountingJob::getExpenseRequestId, expenseRequestId));
            if (job == null) {
                return null;
            }
            ExpenseRequest expense = expenseRequestMapper.selectById(expenseRequestId);
            if (expense == null) {
                // 経費行が存在しない（論理削除等）場合は即時終端させる。
                jobMapper.update(null, new UpdateWrapper<ExpenseAccountingJob>()
                        .eq("expense_request_id", expenseRequestId)
                        .eq("status", STATUS_PROCESSING)
                        .set("status", STATUS_FAILED)
                        .set("last_error_code", "EXPENSE_NOT_FOUND")
                        .set("next_attempt_at", null)
                        .set("updated_at", now));
                return null;
            }
            return new ClaimedJob(job, expense);
        });
    }

    /** 送信成功の結果反映（REQUIRES_NEW）。expense.status=会計連携済 + accounting_job_id + 通知。 */
    public void markSent(Long expenseRequestId, ClaimedJob claimed, String correlationId) {
        requiresNewTx().executeWithoutResult(st -> {
            LocalDateTime now = LocalDateTime.now(clock);
            ExpenseRequest expense = claimed.expense();
            int version = expense.getVersion() == null ? 0 : expense.getVersion();
            int expUpdated = expenseRequestMapper.update(null, new UpdateWrapper<ExpenseRequest>()
                    .eq("id", expense.getId())
                    .eq("status", ExpenseRequestService.STATUS_APPROVED)
                    .eq("version", version)
                    .set("status", ExpenseRequestService.STATUS_ACCOUNTING_SENT)
                    .set("accounting_job_id", claimed.job().getId())
                    .set("version", version + 1)
                    .set("updated_at", now));
            if (expUpdated != 1) {
                throw new IllegalStateException("Expense CAS update failed for expenseId=" + expense.getId());
            }

            int jobUpdated = jobMapper.update(null, new UpdateWrapper<ExpenseAccountingJob>()
                    .eq("expense_request_id", expenseRequestId)
                    .eq("status", STATUS_PROCESSING)
                    .set("status", STATUS_SUCCEEDED)
                    .set("correlation_id", correlationId)
                    .set("sent_at", now)
                    .set("last_error_code", null)
                    .set("next_attempt_at", null)
                    .set("updated_at", now));
            if (jobUpdated != 1) {
                throw new IllegalStateException("Job update failed for expenseRequestId=" + expenseRequestId);
            }
            notifyAccountingSent(expense);
        });
    }

    /** 送信失敗の結果反映（REQUIRES_NEW）。max 5回でFAILED、それ以外はbackoff付きでPENDINGへ戻す。 */
    public void markFailure(Long expenseRequestId, ClaimedJob claimed, String errorCode) {
        requiresNewTx().executeWithoutResult(st -> {
            LocalDateTime now = LocalDateTime.now(clock);
            int attempts = claimed.job().getAttemptCount() == null
                    ? 1 : Math.max(claimed.job().getAttemptCount(), 1);
            if (attempts >= MAX_ATTEMPTS) {
                jobMapper.update(null, new UpdateWrapper<ExpenseAccountingJob>()
                        .eq("expense_request_id", expenseRequestId)
                        .eq("status", STATUS_PROCESSING)
                        .set("status", STATUS_FAILED)
                        .set("last_error_code", errorCode)
                        .set("next_attempt_at", null)
                        .set("updated_at", now));
                log.warn("[経費会計連携] 試行回数上限でFAILED: expenseId={} errorCode={}", expenseRequestId, errorCode);
                return;
            }
            long backoffMinutes = Math.min(60L, 1L << Math.min(Math.max(attempts - 1, 0), 6));
            jobMapper.update(null, new UpdateWrapper<ExpenseAccountingJob>()
                    .eq("expense_request_id", expenseRequestId)
                    .eq("status", STATUS_PROCESSING)
                    .set("status", STATUS_PENDING)
                    .set("last_error_code", errorCode)
                    .set("next_attempt_at", now.plusMinutes(backoffMinutes))
                    .set("updated_at", now));
            log.warn("[経費会計連携] 再試行待ち: expenseId={} attempt={} backoff={}min errorCode={}",
                    expenseRequestId, attempts, backoffMinutes, errorCode);
        });
    }

    /** 本人へ会計連携通知を発行する（dedupeKeyで冪等）。 */
    void notifyAccountingSent(ExpenseRequest expense) {
        Long userId = applicantUserId(expense);
        if (userId == null) {
            return;
        }
        String expenseNo = displayExpenseNo(expense);
        String message = "[\"notification.msg.EXPENSE_ACCOUNTING_SENT\", \"" + expenseNo + "\"]";
        notificationService.publishToUser(userId, "EXPENSE_ACCOUNTING_SENT", "経費を会計へ連携しました",
                message, LINK_URL, "expense-accounting-sent:" + expense.getId(), LINK_MENU_KEY);
    }

    Long applicantUserId(ExpenseRequest expense) {
        com.ses.entity.EngineerAccountLink link = expense == null || expense.getEngineerId() == null
                ? null : engineerAccountLinkService.findByEngineerId(expense.getEngineerId());
        return link == null ? null : link.getSysUserId();
    }

    static String displayExpenseNo(ExpenseRequest expense) {
        return expense.getExpenseNo() == null || expense.getExpenseNo().isBlank()
                ? "EX-" + expense.getId() : expense.getExpenseNo();
    }

    /** 送信payloadのSHA-256（job作成時に固定。sender側の冪等キー）。 */
    String payloadHash(ExpenseRequest expense) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(payloadMap(expense)));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("経費連携payloadのシリアライズに失敗しました", e);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256が利用できません", e);
        }
    }

    private Map<String, Object> payloadMap(ExpenseRequest expense) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("expenseId", expense.getId());
        map.put("expenseNo", expense.getExpenseNo());
        map.put("engineerId", expense.getEngineerId());
        map.put("expenseDate", expense.getExpenseDate() == null ? null : expense.getExpenseDate().toString());
        map.put("category", expense.getCategory());
        map.put("amount", expense.getAmount());
        map.put("customerId", expense.getCustomerId());
        map.put("projectId", expense.getProjectId());
        map.put("description", expense.getDescription());
        map.put("receiptDocumentId", expense.getReceiptDocumentId());
        return map;
    }

    /** claim済みのjobと対象経費の組。 */
    record ClaimedJob(ExpenseAccountingJob job, ExpenseRequest expense) {
    }
}
