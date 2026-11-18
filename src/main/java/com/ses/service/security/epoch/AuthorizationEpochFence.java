package com.ses.service.security.epoch;

import com.ses.common.exception.BusinessException;
import com.ses.mapper.AuthorizationEpochMapper;
import com.ses.mapper.AuthorizationEpochMapper.EpochRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * NF01 細粒度認可epochのwriter側の部品（計画 §2.4 E-1〜E-9、§7.1）。
 *
 * <ul>
 *   <li>E-1: 述語を変える書込みと同じDB transactionで呼ぶ（{@link Propagation#MANDATORY}。transaction外の呼出しは拒否する）。
 *       afterCommitのcache失効は代替にならない。</li>
 *   <li>E-2: writerはanchor行を {@link #lockForUpdate} で先にX lockしてから旧値を読み、変更し、{@link #advance} する。
 *       複数anchorは {@link #advanceAll} がlock順（rank・partition・ID昇順）に並べて進める。</li>
 *   <li>E-4: 変化の無い更新では増やさない（{@link #advanceIfChanged}）。同じanchorは1transactionで1回だけ増やす
 *       （transactionに結び付けた集合で重複を除く。例: updateUser → replaceAssignments の入れ子）。</li>
 *   <li>E-5: 減らす操作は無い。E-6: 上限・行欠落では 409 {@code error.authorization.epochUnavailable} を投げ、
 *       業務更新ごとrollbackさせる。E-8・E-9: 増分SQLは {@code updated_at} と {@code version} を変えない。</li>
 * </ul>
 *
 * <p>W3-10では部品の定義だけで、業務経路からは呼ばない（W3-15以降がwriterへ接続する）。</p>
 */
@Slf4j
@Component
public class AuthorizationEpochFence {

    /** epoch不可用（全体行・anchor行の欠落、上限到達）のmessage key。 */
    public static final String EPOCH_UNAVAILABLE_KEY = "error.authorization.epochUnavailable";

    private static final Object ADVANCED_KEY = new Object();

    private final AuthorizationEpochMapper mapper;

    public AuthorizationEpochFence(AuthorizationEpochMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * anchor行をX lockし、現在のepochを返す（E-2のanchor先行lock）。論理削除済みの行もlockする
     * （削除するwriter自身がlockしてから削除し、epochを進めるため）。
     *
     * @throws BusinessException 409 行が無い（tenant不一致を含む）場合
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public long lockForUpdate(AuthorizationEpochAnchor anchor) {
        Objects.requireNonNull(anchor, "anchor");
        TransactionBoundState.requireActualTransaction();
        EpochRow row = selectForUpdate(anchor);
        if (row == null || row.getEpoch() == null || row.getEpoch() < 0) {
            throw unavailable(anchor, "anchor行が見つかりません");
        }
        return row.getEpoch();
    }

    /**
     * anchorのepochを1進める。同じtransactionで既に進めたanchorは何もしない。
     *
     * @return 今回増分した場合 true、同一transactionで増分済みのため何もしなかった場合 false
     * @throws BusinessException 409 行が無い、または上限に達している場合（値は変えない）
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public boolean advance(AuthorizationEpochAnchor anchor) {
        Objects.requireNonNull(anchor, "anchor");
        Set<AuthorizationEpochAnchor> advanced = advancedInCurrentTransaction();
        if (advanced.contains(anchor)) {
            return false;
        }
        int updated = increment(anchor);
        if (updated != 1) {
            // 0件 = 行が無い（tenant不一致を含む）か上限。どちらも世代を進められないため業務更新ごと止める。
            throw unavailable(anchor, "epochを進められません（行の欠落または上限）");
        }
        advanced.add(anchor);
        return true;
    }

    /**
     * 述語が読む値が変化した場合だけanchorを進める（E-4: no-opでは増やさない）。比較は {@link Objects#equals} で行うため、
     * 集合の比較では呼出側が {@link Set} 等の順序に依存しない型で渡す。
     *
     * @return 今回増分した場合 true
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public boolean advanceIfChanged(AuthorizationEpochAnchor anchor, Object before, Object after) {
        Objects.requireNonNull(anchor, "anchor");
        TransactionBoundState.requireActualTransaction();
        if (Objects.equals(before, after)) {
            return false;
        }
        return advance(anchor);
    }

    /**
     * 複数anchorをlock順（rank・partition・ID昇順）に進める。組織の祖先鎖等、呼出側の列挙順に依存させない。
     *
     * @return 今回増分したanchorの数
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public int advanceAll(Collection<? extends AuthorizationEpochAnchor> anchors) {
        Objects.requireNonNull(anchors, "anchors");
        TransactionBoundState.requireActualTransaction();
        List<AuthorizationEpochAnchor> ordered = anchors.stream()
                .map(anchor -> (AuthorizationEpochAnchor) Objects.requireNonNull(anchor, "anchor"))
                .distinct()
                .sorted(AuthorizationEpochAnchor.LOCK_ORDER)
                .toList();
        int count = 0;
        for (AuthorizationEpochAnchor anchor : ordered) {
            if (advance(anchor)) {
                count++;
            }
        }
        return count;
    }

    private EpochRow selectForUpdate(AuthorizationEpochAnchor anchor) {
        if (anchor instanceof AuthorizationEpochAnchor.Global) {
            return mapper.selectGlobalForUpdate();
        }
        if (anchor instanceof AuthorizationEpochAnchor.Actor actor) {
            return mapper.selectActorForUpdate(actor.userId(), actor.tenantId());
        }
        if (anchor instanceof AuthorizationEpochAnchor.Engineer engineer) {
            return mapper.selectEngineerForUpdate(engineer.engineerId(), engineer.tenantId());
        }
        if (anchor instanceof AuthorizationEpochAnchor.Organization organization) {
            return mapper.selectOrganizationForUpdate(organization.organizationId());
        }
        if (anchor instanceof AuthorizationEpochAnchor.Document document) {
            return mapper.selectDocumentForUpdate(document.documentId(), document.tenantId());
        }
        AuthorizationEpochAnchor.TaskGeneration task = (AuthorizationEpochAnchor.TaskGeneration) anchor;
        return mapper.selectTaskGenerationForUpdate(task.taskId(), task.caseId(), task.tenantId());
    }

    private int increment(AuthorizationEpochAnchor anchor) {
        if (anchor instanceof AuthorizationEpochAnchor.Global) {
            return mapper.incrementGlobal();
        }
        if (anchor instanceof AuthorizationEpochAnchor.Actor actor) {
            return mapper.incrementActor(actor.userId(), actor.tenantId());
        }
        if (anchor instanceof AuthorizationEpochAnchor.Engineer engineer) {
            return mapper.incrementEngineer(engineer.engineerId(), engineer.tenantId());
        }
        if (anchor instanceof AuthorizationEpochAnchor.Organization organization) {
            return mapper.incrementOrganization(organization.organizationId());
        }
        if (anchor instanceof AuthorizationEpochAnchor.Document document) {
            return mapper.incrementDocument(document.documentId(), document.tenantId());
        }
        AuthorizationEpochAnchor.TaskGeneration task = (AuthorizationEpochAnchor.TaskGeneration) anchor;
        return mapper.incrementTaskGeneration(task.taskId(), task.caseId(), task.tenantId());
    }

    private static Set<AuthorizationEpochAnchor> advancedInCurrentTransaction() {
        return TransactionBoundState.current(ADVANCED_KEY, HashSet::new);
    }

    private static BusinessException unavailable(AuthorizationEpochAnchor anchor, String reason) {
        log.warn("[authorization epoch] {}: rank={} id={}", reason, anchor.rank(), anchor.id());
        return BusinessException.of(409, EPOCH_UNAVAILABLE_KEY);
    }
}
