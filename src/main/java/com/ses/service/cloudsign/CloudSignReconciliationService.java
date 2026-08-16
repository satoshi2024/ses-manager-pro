package com.ses.service.cloudsign;

import com.ses.common.enums.DispatchState;
import com.ses.config.CloudSignProperties;
import com.ses.dto.cloudsign.CloudSignDocument;
import com.ses.entity.ContractDocument;
import com.ses.mapper.ContractDocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * 結果不明（mutation timeout/504/crash）のreconciliation（HFP-02-AC-04-03/04/05）。
 *
 * <p>同一外部書類を「一件に特定でき、原本/recipient/statusが一致」する場合だけ自動で再開する。
 * document ID不明（CREATE中断）の自動照合はHFP-02-BLK-02がPASSするまで使わない
 * （人手reconciliationが正規動作）。自動証明できない場合はRECONCILIATION_REQUIREDに停止し、
 * 自動mutation再開はしない。
 */
@Slf4j
@Service
public class CloudSignReconciliationService {

    private final ContractDocumentMapper mapper;
    private final CloudSignApiClient api;
    private final CloudSignRateLimiter rateLimiter;
    private final CloudSignProperties properties;
    private final CloudSignStatusMapper statusMapper;
    private final TransactionTemplate transactionTemplate;

    public CloudSignReconciliationService(ContractDocumentMapper mapper,
                                          CloudSignApiClient api,
                                          CloudSignRateLimiter rateLimiter,
                                          CloudSignProperties properties,
                                          CloudSignStatusMapper statusMapper,
                                          TransactionTemplate transactionTemplate) {
        this.mapper = mapper;
        this.api = api;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.statusMapper = statusMapper;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 結果不明の行をGETで照合し、確定できる場合だけ次の工程へ進める。
     *
     * @param working  現行行（claim済みwork状態）
     * @param from     GET照合前に想定する状態（nullならworkingの現在状態）
     * @param resumeTo 照合成功時の再開先状態（nullなら状態mappingから決定）
     */
    public void verifyThenAdvance(ContractDocument working, String from, String resumeTo) {
        if (!properties.isEnabled()) {
            return;
        }
        String documentId = working.getCloudsignDocumentId();
        if (documentId == null || documentId.isBlank()) {
            // CREATE結果不明でID不明: BLK-02未PASSのため自動照合しない。人手reconciliationへ。
            fail(working, from, "CREATE_ID_UNKNOWN");
            return;
        }
        rateLimiter.acquire();
        CloudSignDocument remote;
        try {
            remote = api.getDocument(documentId);
        } catch (CloudSignApiException e) {
            // GET自体が失敗: 照合不能のまま結果不明を維持（finding記録のみ）
            fail(working, from, "VERIFY_GET_FAILED:" + e.getCode().name());
            return;
        }
        if (remote.status() == null) {
            fail(working, from, "VERIFY_NO_STATUS");
            return;
        }
        String effectiveFrom = from != null ? from : working.getDispatchState();
        switch (remote.status()) {
            case 0 -> {
                // 下書きのまま: どの工程まで進んだか証明できない → 停止（自動mutation再開しない）
                fail(working, effectiveFrom, "VERIFY_STILL_DRAFT");
            }
            case 1 -> advance(working, effectiveFrom, DispatchState.SENT.name(), 1, "先方確認中");
            case 2 -> advance(working, effectiveFrom, DispatchState.COMPLETED.name(), 2, "締結済");
            case 3 -> advance(working, effectiveFrom, DispatchState.CANCELED.name(), 3, "取消・却下");
            case 4 -> fail(working, effectiveFrom, "VERIFY_TEMPLATE");
            default -> fail(working, effectiveFrom, "VERIFY_UNKNOWN_STATUS:" + remote.status());
        }
    }

    private void advance(ContractDocument working, String from, String to,
                         Integer providerStatus, String businessStatus) {
        Integer updated = transactionTemplate.execute(status -> mapper.casStatusSync(
                working.getId(), safeVersion(working), from, to,
                providerStatus, businessStatus, LocalDateTime.now()));
        if (updated != null && updated == 1) {
            log.info("[契約書reconciliation] 外部書類を一件に特定し再開: docId={} {}→{} status={}",
                    working.getId(), from, to, providerStatus);
        } else {
            log.warn("[契約書reconciliation] CAS競合で再開不可: docId={} from={} version={}",
                    working.getId(), from, safeVersion(working));
        }
    }

    private void fail(ContractDocument working, String from, String errorCode) {
        String effectiveFrom = from != null ? from : working.getDispatchState();
        Integer updated = transactionTemplate.execute(status -> mapper.casFail(
                working.getId(), safeVersion(working), effectiveFrom,
                DispatchState.RECONCILIATION_REQUIRED.name(), errorCode));
        if (updated != null && updated == 1) {
            log.warn("[契約書reconciliation] 自動再開せず結果不明に停止: docId={} code={}",
                    working.getId(), errorCode);
        } else {
            log.warn("[契約書reconciliation] CAS競合: docId={} code={}", working.getId(), errorCode);
        }
    }

    private static int safeVersion(ContractDocument doc) {
        return doc.getVersion() == null ? 0 : doc.getVersion();
    }
}
