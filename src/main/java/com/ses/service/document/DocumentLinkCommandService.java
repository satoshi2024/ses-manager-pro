package com.ses.service.document;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.DocumentLink;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.service.security.epoch.AuthorizationEpochAnchor;
import com.ses.service.security.epoch.AuthorizationEpochFence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 文書link（{@code t_document_link}）の書込みを集約する（NF01細粒度epoch計画 §4.3 F3、§4.5 U4c）。
 *
 * <p>文書のlink集合は証跡適格性（§5.1 条件8）の入力である。そのため挿入は、文書行を先にX lockし
 * （lock順 §3.1 の 15 DOCUMENT → 17 DOCUMENT_LINK）、一意key（document_id, target_type, target_id）をlocking readで
 * 確かめてから行い、実際に挿入した時だけ同じtransactionで文書の証跡利用可否epochを進める（E-1、E-2、E-4）。
 * linkのtenantは、lockで照合した文書自身のtenantを設定する（§11 F-3）。呼出元は文書行から読んだtenantを渡すこと
 * （request・sessionの文字列を使わない。W3-10 Review P3-5）。</p>
 *
 * <p>スキルシート確認日時などの付帯情報はlink集合も利用可否も変えないため、epochを進めない（§2.2）。</p>
 *
 * <p>{@code DocumentLinkMapper} の挿入・更新・削除は、本classと {@code DocumentMetadataCommitService}
 * （新規文書の初期link）だけに置く（{@code DocumentEligibilityWriterFenceTest} が固定する）。</p>
 */
@Slf4j
@Service
public class DocumentLinkCommandService {

    /** スキルシートの確認日時を記録するlink先種別。 */
    static final String ENGINEER_TARGET = "ENGINEER";

    private final DocumentLinkMapper documentLinkMapper;
    private final AuthorizationEpochFence fence;

    public DocumentLinkCommandService(DocumentLinkMapper documentLinkMapper, AuthorizationEpochFence fence) {
        this.documentLinkMapper = documentLinkMapper;
        this.fence = fence;
    }

    /**
     * 文書と業務entityのlinkを作る（冪等）。
     *
     * @param tenantId 文書自身のtenant（文書行から読んだ値）。文書行と一致しなければ409 {@code error.authorization.epochUnavailable}
     * @return 今回linkを挿入しepochを進めた場合 true、同じlinkが既にあり何もしなかった場合 false
     * @throws BusinessException 400 link先の指定が無い、403 同じ一意keyのlinkが別tenantで存在する、
     *                           409 論理削除済みの同じlinkと衝突する
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public boolean link(String tenantId, long documentId, String targetType, Long targetId) {
        if (targetType == null || targetType.isBlank() || targetId == null) {
            throw BusinessException.of(400, "error.document.invalidRequest");
        }
        AuthorizationEpochAnchor.Document anchor = AuthorizationEpochAnchor.document(tenantId, documentId);
        // 15 DOCUMENT X: 証跡readerのS lockと直列化し、以後の確認・挿入の間にlink集合を変えさせない。
        fence.lockForUpdate(anchor);
        DocumentLink existing = documentLinkMapper.selectByLinkKeyForUpdate(documentId, targetType, targetId);
        if (existing != null) {
            requireReusable(existing, anchor);
            return false;
        }
        DocumentLink link = new DocumentLink();
        link.setTenantId(anchor.tenantId());
        link.setDocumentId(documentId);
        link.setTargetType(targetType);
        link.setTargetId(targetId);
        documentLinkMapper.insert(link);
        fence.advance(anchor);
        return true;
    }

    /**
     * スキルシート確認の付帯情報（確認日時・確認版）をENGINEER linkへ記録する。link集合も利用可否も変えないため
     * epochは進めない（§2.2「link付帯情報」）。更新する列は付帯情報の2列だけに限る。
     *
     * @return 更新した行数（該当linkが無ければ0）
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public int recordSkillSheetConfirmation(String tenantId, long documentId, long engineerId,
                                            LocalDateTime confirmedAt, String confirmedVersion) {
        AuthorizationEpochAnchor.Document anchor = AuthorizationEpochAnchor.document(tenantId, documentId);
        return documentLinkMapper.update(null, new LambdaUpdateWrapper<DocumentLink>()
                .eq(DocumentLink::getTenantId, anchor.tenantId())
                .eq(DocumentLink::getDocumentId, documentId)
                .eq(DocumentLink::getTargetType, ENGINEER_TARGET)
                .eq(DocumentLink::getTargetId, engineerId)
                .set(DocumentLink::getSkillSheetConfirmedAt, confirmedAt)
                .set(DocumentLink::getSkillSheetConfirmedVersion, confirmedVersion));
    }

    private static void requireReusable(DocumentLink existing, AuthorizationEpochAnchor.Document anchor) {
        if (!anchor.tenantId().equals(existing.getTenantId())) {
            // 一意keyにtenantが含まれないため、別tenantのlinkが残っていると同じlinkを作れない（F-3修正前のデータ）。
            log.warn("[文書link] 同じ一意keyのlinkが文書と異なるtenantで存在するため拒否します: documentId={} linkId={}",
                    anchor.documentId(), existing.getId());
            throw BusinessException.of(403, "error.tenant.mismatch");
        }
        if (existing.getDeletedFlag() != null && existing.getDeletedFlag() != 0) {
            // 現行にlinkを削除するwriterは無い。削除済みの行を復活させる経路は持たず、fail-closedで止める。
            log.warn("[文書link] 論理削除済みの同じlinkと衝突するため拒否します: documentId={} linkId={}",
                    anchor.documentId(), existing.getId());
            throw BusinessException.of(409, "error.document.optimisticLock");
        }
    }
}
