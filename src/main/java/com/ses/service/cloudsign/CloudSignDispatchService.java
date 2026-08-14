package com.ses.service.cloudsign;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.enums.CloudSignErrorCode;
import com.ses.common.enums.DispatchState;
import com.ses.config.CloudSignProperties;
import com.ses.dto.cloudsign.AddParticipantRequest;
import com.ses.dto.cloudsign.CloudSignDocument;
import com.ses.dto.cloudsign.CreateDocumentRequest;
import com.ses.entity.ContractDocument;
import com.ses.mapper.ContractDocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudSign durable dispatch worker（HFP-02-04）。
 *
 * <p>QUEUED以降の操作を一工程ずつ実行し、provider呼出しの成功を短いcheckpoint transactionで
 * commitしてから次へ進む。mutationのtimeout/504/5xxは「結果不明」として自動再実行せず
 * RECONCILIATION_REQUIREDへ送る。provider呼出しは常にDB transaction外（assertでfail-closed）。
 */
@Slf4j
@Service
public class CloudSignDispatchService {

    /** work状態 → 次工程（claim先）の対応。 */
    private static final Map<String, String> CLAIM_TO = Map.of(
            DispatchState.QUEUED.name(), DispatchState.CREATING.name(),
            DispatchState.DOCUMENT_CREATED.name(), DispatchState.UPLOADING.name(),
            DispatchState.FILE_UPLOADED.name(), DispatchState.ADDING_PARTICIPANT.name(),
            DispatchState.READY_TO_SEND.name(), DispatchState.SENDING.name(),
            DispatchState.SENDING.name(), DispatchState.SENDING.name());

    /** work状態 → 429等で戻る親工程。 */
    private static final Map<String, String> RETRY_BACK_TO = Map.of(
            DispatchState.CREATING.name(), DispatchState.QUEUED.name(),
            DispatchState.UPLOADING.name(), DispatchState.DOCUMENT_CREATED.name(),
            DispatchState.ADDING_PARTICIPANT.name(), DispatchState.FILE_UPLOADED.name(),
            DispatchState.SENDING.name(), DispatchState.READY_TO_SEND.name());

    private static final Set<String> DUE_STATES = CLAIM_TO.keySet();

    /** claim中のwork状態（stale claim検出対象）。 */
    private static final Set<String> WORK_STATES = Set.of(
            DispatchState.CREATING.name(),
            DispatchState.UPLOADING.name(),
            DispatchState.ADDING_PARTICIPANT.name(),
            DispatchState.SENDING.name());

    private final ContractDocumentMapper mapper;
    private final CloudSignApiClient api;
    private final CloudSignRateLimiter rateLimiter;
    private final CloudSignProperties properties;
    private final CloudSignStatusMapper statusMapper;
    private final CloudSignReconciliationService reconciliation;
    private final TransactionTemplate transactionTemplate;
    private final String instanceId;
    /** 送信原本PDFの保存root（アプリ共通 app.upload.base-path と同じ値。一元管理）。 */
    private final String uploadBasePath;

    public CloudSignDispatchService(ContractDocumentMapper mapper,
                                    CloudSignApiClient api,
                                    CloudSignRateLimiter rateLimiter,
                                    CloudSignProperties properties,
                                    CloudSignStatusMapper statusMapper,
                                    CloudSignReconciliationService reconciliation,
                                    TransactionTemplate transactionTemplate,
                                    @org.springframework.beans.factory.annotation.Value("${app.upload.base-path:./uploads}") String uploadBasePath) {
        this.mapper = mapper;
        this.api = api;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.statusMapper = statusMapper;
        this.reconciliation = reconciliation;
        this.transactionTemplate = transactionTemplate;
        this.uploadBasePath = uploadBasePath;
        String configured = properties.getInstanceId();
        this.instanceId = configured != null && !configured.isBlank()
                ? configured
                : java.net.InetAddress.getLoopbackAddress().getHostName() + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    /** kill switch: disabledなら何もしない（HFP-02-AC-12-03）。 */
    public void dispatchDue(int limit) {
        if (!properties.isEnabled()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<ContractDocument> due = mapper.selectList(new LambdaQueryWrapper<ContractDocument>()
                .in(ContractDocument::getDispatchState, DUE_STATES)
                .and(w -> w.isNull(ContractDocument::getNextAttemptAt)
                        .or().le(ContractDocument::getNextAttemptAt, now))
                .orderByAsc(ContractDocument::getId)
                .last("LIMIT " + Math.max(1, limit)));
        for (ContractDocument doc : due) {
            try {
                String to = CLAIM_TO.get(doc.getDispatchState());
                if (to == null) {
                    continue;
                }
                int claimed = mapper.casClaim(doc.getId(), safeVersion(doc),
                        doc.getDispatchState(), to, now, instanceId, now);
                if (claimed == 0) {
                    continue; // 他workerがclaim済み（version/state CASで二重処理なし）
                }
                runStep(mapper.selectById(doc.getId()));
            } catch (RuntimeException e) {
                log.warn("[契約書dispatch] 処理中に例外: docId={} error={}",
                        doc.getId(), safeError(e));
            }
        }
        reconcileStaleClaims(now);
    }

    /**
     * work状態の工程を実行する。実行後、checkpoint CASで次の状態へ進む。
     * provider呼出しは常にtransaction外（assertNoTransactionでfail-closed）。
     */
    void runStep(ContractDocument working) {
        if (working == null) {
            return;
        }
        String state = working.getDispatchState();
        try {
            switch (state) {
                case "CREATING" -> doCreate(working);
                case "UPLOADING" -> doUpload(working);
                case "ADDING_PARTICIPANT" -> doParticipant(working);
                case "SENDING" -> doSend(working);
                default -> log.warn("[契約書dispatch] 想定外の状態: docId={} state={}",
                        working.getId(), state);
            }
        } catch (CloudSignApiException e) {
            handleApiFailure(working, e);
        }
    }

    private void doCreate(ContractDocument working) {
        if (!payloadAndSourceStillValid(working)) {
            return; // findingはpayloadAndSourceStillValid内で記録済み
        }
        assertNoTransaction();
        rateLimiter.acquire();
        try {
            CloudSignDocument created = api.createDocument(
                    new CreateDocumentRequest(titleOf(working), "op:" + working.getOperationId(), null));
            checkpoint(working, DispatchState.CREATING.name(), DispatchState.DOCUMENT_CREATED.name(),
                    created.id(), null, null, created.status());
        } catch (CloudSignApiException e) {
            handleApiFailure(working, e);
        }
    }

    private void doUpload(ContractDocument working) {
        if (!payloadAndSourceStillValid(working)) {
            return;
        }
        assertNoTransaction();
        rateLimiter.acquire();
        try {
            byte[] pdf = readValidatedSourcePdf(working);
            CloudSignDocument after = api.uploadFile(working.getCloudsignDocumentId(),
                    fileNameOf(working), pdf);
            String fileId = after.files() != null && !after.files().isEmpty()
                    ? after.files().get(0).id() : null;
            if (fileId == null) {
                fail(working, DispatchState.UPLOADING.name(), DispatchState.RECONCILIATION_REQUIRED.name(),
                        "UPLOAD_NO_FILE_ID");
                return;
            }
            checkpoint(working, DispatchState.UPLOADING.name(), DispatchState.FILE_UPLOADED.name(),
                    null, fileId, null, after.status());
        } catch (CloudSignApiException e) {
            handleApiFailure(working, e);
        }
    }

    private void doParticipant(ContractDocument working) {
        if (!payloadAndSourceStillValid(working)) {
            return;
        }
        assertNoTransaction();
        rateLimiter.acquire();
        try {
            CloudSignDocument after = api.addParticipant(working.getCloudsignDocumentId(),
                    new AddParticipantRequest(working.getRecipientName(), working.getRecipientEmail(),
                            null, "ja"));
            String participantId = after.participants() != null && !after.participants().isEmpty()
                    ? after.participants().get(after.participants().size() - 1).id() : null;
            if (participantId == null) {
                fail(working, DispatchState.ADDING_PARTICIPANT.name(),
                        DispatchState.RECONCILIATION_REQUIRED.name(), "PARTICIPANT_NO_ID");
                return;
            }
            checkpoint(working, DispatchState.ADDING_PARTICIPANT.name(),
                    DispatchState.READY_TO_SEND.name(), null, null, participantId, after.status());
        } catch (CloudSignApiException e) {
            handleApiFailure(working, e);
        }
    }

    /**
     * SENDING工程: preflight GET → 送信 → 反映待ち → GETでstatus確定 → checkpoint。
     * 送信後のtimeout/5xxは結果不明（GET照合のみ。再POST禁止=reminder防止）。
     */
    private void doSend(ContractDocument working) {
        assertNoTransaction();
        rateLimiter.acquire();
        CloudSignDocument preflight;
        try {
            preflight = api.getDocument(working.getCloudsignDocumentId());
        } catch (CloudSignApiException e) {
            handleApiFailure(working, e);
            return;
        }
        if (preflight.status() == null || preflight.status() != 0
                || !preflight.hasFileId(working.getCloudsignFileId())
                || !preflight.hasParticipantId(working.getCloudsignParticipantId())) {
            fail(working, DispatchState.SENDING.name(), DispatchState.RECONCILIATION_REQUIRED.name(),
                    "PREFLIGHT_MISMATCH");
            return;
        }

        rateLimiter.acquire();
        CloudSignDocument sent;
        try {
            sent = api.sendDocument(working.getCloudsignDocumentId());
        } catch (CloudSignApiException e) {
            // 送信mutationの結果不明: GET照合（自動再POSTしない）
            if (e.isUncertain()) {
                reconciliation.verifyThenAdvance(working, DispatchState.SENDING.name(),
                        DispatchState.SENT.name());
                return;
            }
            handleApiFailure(working, e);
            return;
        }

        try {
            Thread.sleep(Math.max(0, properties.getMutationReflectWaitMs()));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            fail(working, DispatchState.SENDING.name(), DispatchState.RECONCILIATION_REQUIRED.name(),
                    "SEND_WAIT_INTERRUPTED");
            return;
        }
        rateLimiter.acquire();
        try {
            CloudSignDocument confirmed = api.getDocument(working.getCloudsignDocumentId());
            if (confirmed.status() != null && confirmed.status() == 1) {
                statusSync(working, DispatchState.SENT.name(), 1, "先方確認中");
            } else if (confirmed.status() != null && confirmed.status() == 2) {
                statusSync(working, DispatchState.SENT.name(), 2, "締結済");
            } else if (confirmed.status() != null && confirmed.status() == 3) {
                statusSync(working, DispatchState.CANCELED.name(), 3, "取消・却下");
            } else {
                // 0のまま = 送信が反映されていない。運用確認後にのみ再操作（自動再送禁止）
                fail(working, DispatchState.SENDING.name(),
                        DispatchState.RECONCILIATION_REQUIRED.name(), "SEND_STILL_DRAFT");
            }
        } catch (CloudSignApiException e) {
            // GET失敗（timeout等）: 送信結果不明のままGET照合へ
            reconciliation.verifyThenAdvance(working, DispatchState.SENDING.name(),
                    DispatchState.SENT.name());
        }
    }

    private void handleApiFailure(ContractDocument working, CloudSignApiException e) {
        if (e.isUncertain()) {
            // 結果不明: 自動mutation再実行しない。
            if (working.getCloudsignDocumentId() == null || working.getCloudsignDocumentId().isBlank()) {
                // CREATE中断でID不明: BLK-02未PASSのため自動照合せず人手reconciliation
                fail(working, working.getDispatchState(), DispatchState.RECONCILIATION_REQUIRED.name(),
                        e.getCode().name());
            } else {
                reconciliation.verifyThenAdvance(working, working.getDispatchState(), null);
            }
            return;
        }
        if (e.getCode() == CloudSignErrorCode.RATE_LIMITED) {
            retryWait(working, e.getCode().name());
            return;
        }
        if (e.getCode() == CloudSignErrorCode.UNAUTHORIZED
                || e.getCode() == CloudSignErrorCode.INVALID_CLIENT) {
            fail(working, working.getDispatchState(), DispatchState.FAILED_FINAL.name(),
                    "CREDENTIAL_FAILURE");
            return;
        }
        // 確定失敗（4xx validation/permission等）: 自動再試行しない
        fail(working, working.getDispatchState(), DispatchState.FAILED_FINAL.name(),
                e.getCode().name());
    }

    /** 429等の「受理されなかった」失敗はbounded backoffで親工程へ戻す（attempt上限あり）。 */
    private void retryWait(ContractDocument working, String errorCode) {
        if (safeVersion(working) >= properties.getMaxAttempts()) {
            fail(working, working.getDispatchState(), DispatchState.FAILED_FINAL.name(),
                    "ATTEMPT_LIMIT:" + errorCode);
            return;
        }
        String backTo = RETRY_BACK_TO.get(working.getDispatchState());
        if (backTo == null) {
            fail(working, working.getDispatchState(), DispatchState.RECONCILIATION_REQUIRED.name(),
                    errorCode);
            return;
        }
        int updated = inTransaction(() -> mapper.casRetryWait(working.getId(), safeVersion(working),
                working.getDispatchState(), backTo, errorCode,
                LocalDateTime.now().plusMinutes(1)));
        if (updated == 0) {
            logFinding(working, "CAS_FAILED_RETRY_WAIT");
        } else {
            log.info("[契約書dispatch] rate limit待機へ遷移: docId={} error={} nextAttemptAt=1分後",
                    working.getId(), errorCode);
        }
    }

    /** checkpoint CASを短いtransactionで実行する。provider呼出し後、結果を確定してから次工程へ進む。 */
    private void checkpoint(ContractDocument working, String from, String to,
                            String documentId, String fileId, String participantId, Integer status) {
        int updated = inTransaction(() -> mapper.casCheckpoint(working.getId(), safeVersion(working),
                from, to, documentId, fileId, participantId, status));
        if (updated == 0) {
            logFinding(working, "CAS_FAILED_CHECKPOINT");
        } else {
            log.info("[契約書dispatch] checkpoint完了: docId={} {}→{}", working.getId(), from, to);
        }
    }

    private void statusSync(ContractDocument working, String to, Integer providerStatus, String businessStatus) {
        int updated = inTransaction(() -> mapper.casStatusSync(working.getId(), safeVersion(working),
                working.getDispatchState(), to, providerStatus, businessStatus, LocalDateTime.now()));
        if (updated == 0) {
            logFinding(working, "CAS_FAILED_STATUS_SYNC");
        }
    }

    private void fail(ContractDocument working, String from, String to, String errorCode) {
        int updated = inTransaction(() -> mapper.casFail(working.getId(), safeVersion(working),
                from, to, errorCode));
        if (updated == 0) {
            logFinding(working, "CAS_FAILED_FAIL");
        } else {
            log.warn("[契約書dispatch] 状態遷移: docId={} {}→{} code={}", working.getId(), from, to, errorCode);
        }
    }

    private void logFinding(ContractDocument working, String finding) {
        log.warn("[契約書dispatch] finding: docId={} finding={} state={} version={}",
                working.getId(), finding, working.getDispatchState(), safeVersion(working));
    }

    /** 送信原本PDFがqueue時と同一かを再検証（AC-03-01/AC-03-05）。 */
    private boolean payloadAndSourceStillValid(ContractDocument working) {
        if (working.getPdfSha256() == null || working.getSendPayloadSha256() == null) {
            fail(working, working.getDispatchState(), DispatchState.RECONCILIATION_REQUIRED.name(),
                    "PAYLOAD_INCOMPLETE");
            return false;
        }
        Path pdf = normalizePath(working.getPdfPath());
        if (pdf == null || !Files.isRegularFile(pdf)) {
            fail(working, working.getDispatchState(), DispatchState.RECONCILIATION_REQUIRED.name(),
                    "SOURCE_MISSING");
            return false;
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(pdf);
        } catch (Exception e) {
            fail(working, working.getDispatchState(), DispatchState.RECONCILIATION_REQUIRED.name(),
                    "SOURCE_UNREADABLE");
            return false;
        }
        if (bytes.length > properties.getMaxPdfBytes() || !isPdfBytes(bytes)) {
            fail(working, working.getDispatchState(), DispatchState.RECONCILIATION_REQUIRED.name(),
                    "SOURCE_INVALID");
            return false;
        }
        String currentHash = sha256Hex(bytes);
        if (!currentHash.equals(working.getPdfSha256())) {
            fail(working, working.getDispatchState(), DispatchState.RECONCILIATION_REQUIRED.name(),
                    "SOURCE_HASH_CHANGED");
            return false;
        }
        return true;
    }

    byte[] readValidatedSourcePdf(ContractDocument working) {
        Path pdf = normalizePath(working.getPdfPath());
        if (pdf == null) {
            throw new CloudSignApiException(CloudSignErrorCode.UNKNOWN, "SOURCE_MISSING");
        }
        try {
            return Files.readAllBytes(pdf);
        } catch (Exception e) {
            throw new CloudSignApiException(CloudSignErrorCode.NETWORK, "SOURCE_UNREADABLE");
        }
    }

    /** upload root内の正規化pathのみ許可（path traversal防止）。root外はnull。 */
    Path normalizePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            Path root = Paths.get(uploadBasePath).toAbsolutePath().normalize();
            Path target = Paths.get(raw).toAbsolutePath().normalize();
            return target.startsWith(root) ? target : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String titleOf(ContractDocument working) {
        return "SES契約書 " + working.getContractId();
    }

    private String fileNameOf(ContractDocument working) {
        Path p = normalizePath(working.getPdfPath());
        return p == null || p.getFileName() == null ? "document.pdf" : p.getFileName().toString();
    }

    /** stale claim検出: claim保持のまま長時間経過したwork行は結果不明へ（自動未実行へ戻さない）。 */
    void reconcileStaleClaims(LocalDateTime now) {
        LocalDateTime threshold = now.minusMinutes(Math.max(1, properties.getStaleClaimMinutes()));
        List<ContractDocument> stale = mapper.selectList(new LambdaQueryWrapper<ContractDocument>()
                .in(ContractDocument::getDispatchState, WORK_STATES)
                .isNotNull(ContractDocument::getClaimedAt)
                .lt(ContractDocument::getClaimedAt, threshold));
        for (ContractDocument doc : stale) {
            int updated = inTransaction(() -> mapper.casFail(doc.getId(), safeVersion(doc),
                    doc.getDispatchState(), DispatchState.RECONCILIATION_REQUIRED.name(),
                    "STALE_CLAIM"));
            if (updated == 1) {
                log.warn("[契約書dispatch] stale claimを結果不明へ: docId={} state={} claimedAt={} owner={}",
                        doc.getId(), doc.getDispatchState(), doc.getClaimedAt(), maskedOwner(doc.getClaimOwner()));
            }
        }
    }

    private void assertNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "CloudSign provider呼出しがtransaction内で実行されました（fail-closed）: docId=" + hashCode());
        }
    }

    private <T> T inTransaction(java.util.function.Supplier<T> action) {
        return transactionTemplate.execute(status -> action.get());
    }

    private static int safeVersion(ContractDocument doc) {
        return doc.getVersion() == null ? 0 : doc.getVersion();
    }

    private static String safeError(Throwable t) {
        String message = t.getMessage();
        return message == null ? t.getClass().getSimpleName() : message;
    }

    private static String maskedOwner(String owner) {
        return owner == null ? null : owner.substring(0, Math.min(8, owner.length()));
    }

    private static boolean isPdfBytes(byte[] data) {
        if (data.length < 8) {
            return false;
        }
        if (data[0] != 0x25 || data[1] != 0x50 || data[2] != 0x44 || data[3] != 0x46 || data[4] != 0x2D) {
            return false;
        }
        String tail = new String(data, Math.max(0, data.length - 1024), Math.min(data.length, 1024),
                StandardCharsets.ISO_8859_1);
        int eof = tail.lastIndexOf("%%EOF");
        return eof >= 0 && tail.substring(eof + 5).trim().isEmpty();
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte v : digest) {
                sb.append(String.format("%02x", v));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
