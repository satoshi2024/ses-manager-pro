package com.ses.service.security.epoch;

import com.ses.common.exception.BusinessException;
import com.ses.mapper.AuthorizationEpochMapper;
import com.ses.mapper.AuthorizationEpochMapper.EpochRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * NF01 細粒度認可epochのreader側の部品（計画 §2.5）。CONFIRMのsubmit/confirm attempt
 * （{@code REQUIRES_NEW} + {@code READ_COMMITTED}）の中で呼び、全体・利用者・組織・要員・文書の各anchor行を
 * {@code FOR SHARE} で読んでlockを保持したまま現在値を返す。タスク世代はreaderもX lockで読む（case X → task X）。
 *
 * <p>行が無い・tenantが一致しない・論理削除済みの場合はanchor不在としてfail-closedにし、
 * 409 {@code error.authorization.epochUnavailable} を投げる（計画 §2.6、§5.9）。
 * 子行（営業割当・account link・所属・会計履歴・relation history・文書link・廃棄申請）はlockしない。anchorのS lockを
 * 保持している間は子行のwriterがanchorのXを取れないため、READ_COMMITTEDの通常読取で現在値が安定する。</p>
 *
 * <p>{@code FOR SHARE} はH2で解釈されないため、本クラスはMySQLでだけ実行する（fast testはmockする）。
 * W3-10では部品の定義だけで、業務経路からは呼ばない。</p>
 */
@Slf4j
@Component
public class AuthorizationEpochReader {

    private final AuthorizationEpochMapper mapper;

    public AuthorizationEpochReader(AuthorizationEpochMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public long readGlobal() {
        TransactionBoundState.requireActualTransaction();
        return require(mapper.selectGlobalForShare(), AuthorizationEpochAnchor.global());
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public long readActor(String tenantId, long userId) {
        TransactionBoundState.requireActualTransaction();
        AuthorizationEpochAnchor.Actor anchor = AuthorizationEpochAnchor.actor(tenantId, userId);
        return require(mapper.selectActorForShare(anchor.userId(), anchor.tenantId()), anchor);
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public long readEngineer(String tenantId, long engineerId) {
        TransactionBoundState.requireActualTransaction();
        AuthorizationEpochAnchor.Engineer anchor = AuthorizationEpochAnchor.engineer(tenantId, engineerId);
        return require(mapper.selectEngineerForShare(anchor.engineerId(), anchor.tenantId()), anchor);
    }

    /**
     * 組織部分木のepochをID昇順に読む（lock順 §3.5 規則3）。重複IDは1回だけ読む。
     *
     * @return 組織ID → epoch（ID昇順、変更不可）
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public SortedMap<Long, Long> readOrganizations(Collection<Long> organizationIds) {
        Objects.requireNonNull(organizationIds, "organizationIds");
        TransactionBoundState.requireActualTransaction();
        SortedMap<Long, Long> epochs = new TreeMap<>();
        for (Long organizationId : new TreeSet<>(organizationIds)) {
            AuthorizationEpochAnchor.Organization anchor =
                    AuthorizationEpochAnchor.organization(Objects.requireNonNull(organizationId, "organizationId"));
            epochs.put(organizationId, require(mapper.selectOrganizationForShare(anchor.organizationId()), anchor));
        }
        return Collections.unmodifiableSortedMap(epochs);
    }

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public long readDocument(String tenantId, long documentId) {
        TransactionBoundState.requireActualTransaction();
        AuthorizationEpochAnchor.Document anchor = AuthorizationEpochAnchor.document(tenantId, documentId);
        return require(mapper.selectDocumentForShare(anchor.documentId(), anchor.tenantId()), anchor);
    }

    /** タスク世代をX lockで読む。所属案件を同じtransactionで先にX lockしていること（case X → task X）。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public long readTaskGeneration(String tenantId, long caseId, long taskId) {
        TransactionBoundState.requireActualTransaction();
        AuthorizationEpochAnchor.TaskGeneration anchor = AuthorizationEpochAnchor.taskGeneration(tenantId, caseId, taskId);
        return require(mapper.selectTaskGenerationForUpdate(anchor.taskId(), anchor.caseId(), anchor.tenantId()), anchor);
    }

    private static long require(EpochRow row, AuthorizationEpochAnchor anchor) {
        if (row == null || !row.usable()) {
            log.warn("[authorization epoch] anchor行を読めません（欠落・tenant不一致・論理削除）: rank={} id={}",
                    anchor.rank(), anchor.id());
            throw BusinessException.of(409, AuthorizationEpochFence.EPOCH_UNAVAILABLE_KEY);
        }
        return row.getEpoch();
    }
}
