package com.ses.service.cloudsign;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.enums.CloudSignErrorCode;
import com.ses.common.enums.DispatchState;
import com.ses.config.CloudSignProperties;
import com.ses.dto.cloudsign.CloudSignDocument;
import com.ses.entity.ContractDocument;
import com.ses.mapper.ContractDocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 送信後状態の同期（HFP-02-05）。poll schedulerとmanual syncの両方が同じservice/mappingを使い、
 * provider GETはtransaction外、保存はversion CASで行う（HFP-02-AC-06-01/02）。
 * terminal状態からの逆戻り・締結後再送・意図しないreminderを拒否する。
 */
@Slf4j
@Service
public class CloudSignSyncService {

    /**
     * poll対象: 送信済み(SENT)のみ。
     * RECONCILIATION_REQUIREDはdesign §6.2の3条件（一意特定＋原本/recipient一致＋reviewer監査）を
     * 満たす専用操作（manual sync）だけで復旧する。pollが自動遷移してはならない（HFP-02-AC-04-05）。
     */
    private static final Set<String> POLL_STATES = Set.of(
            DispatchState.SENT.name());

    private static final Set<String> TERMINAL_STATES = Set.of(
            DispatchState.COMPLETED.name(),
            DispatchState.CANCELED.name(),
            DispatchState.FAILED_FINAL.name());

    private final ContractDocumentMapper mapper;
    private final CloudSignApiClient api;
    private final CloudSignRateLimiter rateLimiter;
    private final CloudSignProperties properties;
    private final CloudSignStatusMapper statusMapper;
    private final CloudSignMonitor monitor;
    private final TransactionTemplate transactionTemplate;

    public CloudSignSyncService(ContractDocumentMapper mapper,
                                CloudSignApiClient api,
                                CloudSignRateLimiter rateLimiter,
                                CloudSignProperties properties,
                                CloudSignStatusMapper statusMapper,
                                CloudSignMonitor monitor,
                                TransactionTemplate transactionTemplate) {
        this.mapper = mapper;
        this.api = api;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.statusMapper = statusMapper;
        this.monitor = monitor;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 一件の状態をprovider GETで同期する（manual sync / poll共用）。
     * terminal行・外部ID未設定行はGETせずそのまま（逆戻り・不要呼出し防止）。
     */
    public void syncDocument(Long id) {
        if (!properties.isEnabled()) {
            return;
        }
        ContractDocument doc = mapper.selectById(id);
        if (doc == null) {
            return;
        }
        String state = doc.getDispatchState();
        if (TERMINAL_STATES.contains(state)) {
            return; // terminal逆戻り禁止（HFP-02-AC-05-03）
        }
        String documentId = doc.getCloudsignDocumentId();
        if (documentId == null || documentId.isBlank()) {
            return; // 未送信/送信前は対象外
        }
        rateLimiter.acquire();
        CloudSignDocument remote;
        try {
            remote = api.getDocument(documentId);
        } catch (CloudSignApiException e) {
            monitor.recordError(e.getCode());
            handleGetFailure(doc, e);
            return;
        }
        applyRemote(doc, remote);
    }

    /** poll対象のactive行を古い順にbatchで同期する。一行の失敗でbatch全体を止めない。 */
    public int pollDue(int limit) {
        if (!properties.isEnabled()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        java.util.List<ContractDocument> due = mapper.selectList(new LambdaQueryWrapper<ContractDocument>()
                .in(ContractDocument::getDispatchState, POLL_STATES)
                .isNotNull(ContractDocument::getCloudsignDocumentId)
                .and(w -> w.isNull(ContractDocument::getNextAttemptAt)
                        .or().le(ContractDocument::getNextAttemptAt, now))
                .orderByAsc(ContractDocument::getLastSyncedAt)
                .orderByAsc(ContractDocument::getId)
                .last("LIMIT " + Math.max(1, limit)));
        int processed = 0;
        for (ContractDocument doc : due) {
            try {
                syncDocument(doc.getId());
                processed++;
            } catch (RuntimeException e) {
                log.warn("[契約書sync] 一行の同期失敗をbatch全体へ波及させない: docId={} error={}",
                        doc.getId(), safeError(e));
            }
        }
        return processed;
    }

    private void applyRemote(ContractDocument doc, CloudSignDocument remote) {
        Integer status = remote.status();
        if (status == null) {
            recordFinding(doc, "POLL_NO_STATUS");
            return;
        }
        String business = statusMapper.businessStatus(status);
        if (DispatchState.RECONCILIATION_REQUIRED.name().equals(doc.getDispatchState())) {
            // 専用操作（manual sync）: 同一外部書類の一意特定＋原本/recipient一致を証明できた場合のみ復旧。
            // 一致を証明できない場合は状態を維持し、誤宛先書類を締結済へ確定しない（REV-002）。
            if (remoteStatusIsTerminal(status) && remoteMatchesSent(doc, remote)) {
                syncTo(doc, terminalStateOf(status), status, business);
            } else {
                recordFinding(doc, "VERIFY_MISMATCH:" + status);
            }
            return;
        }
        switch (status) {
            case 1 -> syncTo(doc, DispatchState.SENT.name(), 1, business);
            case 2 -> syncTo(doc, DispatchState.COMPLETED.name(), 2, business);
            case 3 -> syncTo(doc, DispatchState.CANCELED.name(), 3, business);
            case 4 -> recordFinding(doc, "POLL_TEMPLATE");
            case 0 -> recordFinding(doc, "POLL_REVERSAL_DRAFT");
            default -> {
                monitor.recordUnknownStatus();
                recordFinding(doc, "POLL_UNKNOWN_STATUS:" + status);
            }
        }
    }

    private static boolean remoteStatusIsTerminal(Integer status) {
        return status != null && (status == 1 || status == 2 || status == 3);
    }

    private static String terminalStateOf(Integer status) {
        return switch (status) {
            case 2 -> DispatchState.COMPLETED.name();
            case 3 -> DispatchState.CANCELED.name();
            default -> DispatchState.SENT.name();
        };
    }

    /**
     * 送信時file IDと宛先（email）がremoteに存在することを証明する。
     * これが揃わない限り「同一外部書類を一件に特定・原本/recipient一致」とはみなさない。
     */
    private static boolean remoteMatchesSent(ContractDocument doc, CloudSignDocument remote) {
        if (doc.getCloudsignFileId() == null || doc.getCloudsignFileId().isBlank()) {
            return false;
        }
        if (!remote.hasFileId(doc.getCloudsignFileId())) {
            return false;
        }
        if (doc.getRecipientEmail() == null || doc.getRecipientEmail().isBlank()) {
            return false;
        }
        return remote.participants() != null && remote.participants().stream()
                .anyMatch(p -> doc.getRecipientEmail().equalsIgnoreCase(p.email()));
    }

    private void syncTo(ContractDocument doc, String to, Integer providerStatus, String businessStatus) {
        Integer updated = transactionTemplate.execute(status -> mapper.casStatusSync(
                doc.getId(), safeVersion(doc), doc.getDispatchState(), to,
                providerStatus, businessStatus, LocalDateTime.now()));
        if (updated != null && updated == 1) {
            log.info("[契約書sync] 状態同期: docId={} {}→{} status={}",
                    doc.getId(), doc.getDispatchState(), to, providerStatus);
        } else {
            // manual syncとpollのcommit順反転: 既に他方が確定済み。CASで逆戻りしない。
            log.warn("[契約書sync] CAS競合で状態を維持（逆戻りなし）: docId={} from={} version={}",
                    doc.getId(), doc.getDispatchState(), safeVersion(doc));
        }
    }

    /** 安全側の要確認遷移: dispatch状態は維持し、status/error codeだけ更新する。 */
    private void recordFinding(ContractDocument doc, String code) {
        Integer updated = transactionTemplate.execute(status -> mapper.casStatusFinding(
                doc.getId(), safeVersion(doc), doc.getDispatchState(),
                doc.getCloudsignStatus(), "要確認", code));
        if (updated != null && updated == 1) {
            log.warn("[契約書sync] 未知/逆戻り状態を要確認に記録: docId={} code={}", doc.getId(), code);
        }
    }

    /** GET失敗（5xx/timeout/429）: bounded backoff。4xx確定はFAILED_FINAL。 */
    private void handleGetFailure(ContractDocument doc, CloudSignApiException e) {
        String state = doc.getDispatchState();
        if (e.getCode() == CloudSignErrorCode.RATE_LIMITED) {
            backoff(doc, state, "RATE_LIMITED", 60);
            return;
        }
        if (e.getCode() == CloudSignErrorCode.SERVER_ERROR
                || e.getCode() == CloudSignErrorCode.TIMEOUT
                || e.getCode() == CloudSignErrorCode.NETWORK) {
            int attempts = safeAttempts(doc);
            if (attempts >= Math.max(1, properties.getMaxAttempts())) {
                failFinal(doc, state, "GET_ATTEMPT_LIMIT:" + e.getCode().name());
            } else {
                backoff(doc, state, "GET:" + e.getCode().name(), 60);
            }
            return;
        }
        if (e.getCode() == CloudSignErrorCode.UNAUTHORIZED
                || e.getCode() == CloudSignErrorCode.INVALID_CLIENT) {
            monitor.recordError(CloudSignErrorCode.UNAUTHORIZED);
            failFinal(doc, state, "CREDENTIAL_FAILURE");
            return;
        }
        // 4xx等の確定失敗: 再試行しない
        failFinal(doc, state, e.getCode().name());
    }

    private void backoff(ContractDocument doc, String state, String code, long minutes) {
        Integer updated = transactionTemplate.execute(status -> mapper.casGetBackoff(
                doc.getId(), safeVersion(doc), state, code,
                LocalDateTime.now().plusMinutes(minutes)));
        if (updated != null && updated == 1) {
            log.warn("[契約書sync] GET backoff設定: docId={} code={} next={}分後", doc.getId(), code, minutes);
        }
    }

    private void failFinal(ContractDocument doc, String state, String code) {
        Integer updated = transactionTemplate.execute(status -> mapper.casFail(
                doc.getId(), safeVersion(doc), state,
                DispatchState.FAILED_FINAL.name(), code));
        if (updated != null && updated == 1) {
            log.warn("[契約書sync] 恒久エラー: docId={} code={}", doc.getId(), code);
        }
    }

    private static int safeVersion(ContractDocument doc) {
        return doc.getVersion() == null ? 0 : doc.getVersion();
    }

    private static int safeAttempts(ContractDocument doc) {
        return doc.getDispatchAttemptCount() == null ? 0 : doc.getDispatchAttemptCount();
    }

    private static String safeError(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }
}
