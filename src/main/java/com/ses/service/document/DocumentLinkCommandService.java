package com.ses.service.document;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Document;
import com.ses.entity.DocumentLink;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.service.security.epoch.AuthorizationEpochAnchor;
import com.ses.service.security.epoch.AuthorizationEpochFence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 文書link（{@code t_document_link}）の書込みを集約する（NF01細粒度epoch計画 §4.3 F3、§4.5 U4c）。
 *
 * <p>文書のlink集合は証跡適格性（§5.1 条件8）の入力である。そのため挿入は、文書行を先にX lockしてから
 * （lock順 §3.1 の 15 DOCUMENT → 17 DOCUMENT_LINK）行い、実際に挿入した時だけ同じtransactionで文書の証跡利用可否epochを
 * 進める（E-1、E-2、E-4）。linkのtenantは、lockで照合した文書自身のtenantを設定する（§11 F-3）。呼出元は文書行から読んだ
 * tenantを渡すこと（request・sessionの文字列を使わない。W3-10 Review P3-5）。</p>
 *
 * <p>挿入は「先にinsertし、一意keyの重複を検出した時だけ既存行をlocking readで確かめる」順で行う。一意index
 * {@code uk_document_link (document_id, target_type, target_id)} は全文書で共有されるため、REPEATABLE READで未命中の
 * locking readを先に行うと、新しいkeyが並ぶgapにgap lockが掛かり、別文書のlink挿入（insert intention）まで待たせ、
 * 同じtransactionで2件のlinkを作る経路（経費の領収書）では1213になり得る。重複検出後の行は必ず存在するのでrecord lockだけを取り、
 * 同じ文書のwriterは文書行のX lockで直列化済みのため、S→X昇格による循環も生じない。重複の例外はこのclassの中で受け止め、
 * transaction proxyを越えさせない（rollback-onlyにしない）。</p>
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
    static final String TARGET_REQUIRED_KEY = "error.document.linkTargetRequired";
    static final String DELETED_CONFLICT_KEY = "error.document.linkDeletedConflict";

    private final DocumentLinkMapper documentLinkMapper;
    private final DocumentMapper documentMapper;
    private final AuthorizationEpochFence fence;

    public DocumentLinkCommandService(DocumentLinkMapper documentLinkMapper, DocumentMapper documentMapper,
                                      AuthorizationEpochFence fence) {
        this.documentLinkMapper = documentLinkMapper;
        this.documentMapper = documentMapper;
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
            throw BusinessException.of(400, TARGET_REQUIRED_KEY);
        }
        AuthorizationEpochAnchor.Document anchor = AuthorizationEpochAnchor.document(tenantId, documentId);
        // 15 DOCUMENT X: 証跡readerのS lockと直列化し、以後の挿入・確認の間にlink集合を変えさせない。
        fence.lockForUpdate(anchor);
        DocumentLink link = new DocumentLink();
        link.setTenantId(anchor.tenantId());
        link.setDocumentId(documentId);
        link.setTargetType(targetType);
        link.setTargetId(targetId);
        try {
            documentLinkMapper.insert(link);
        } catch (DuplicateKeyException duplicate) {
            // 重複を検出した後なので既存行は必ずあり、locking readはrecord lockだけを取る（gap lockを取らない）。
            DocumentLink existing = documentLinkMapper.selectByLinkKeyForUpdate(documentId, targetType, targetId);
            if (existing == null) {
                // 一意key以外での重複（主キー等）は想定外のため、そのまま失敗させる。
                throw duplicate;
            }
            requireReusable(existing, anchor);
            return false;
        }
        fence.advance(anchor);
        return true;
    }

    /**
     * 文書IDだけを受け取る呼出元（資産貸与・返却の証跡）のためにlinkを作る。文書行からtenantを読み、
     * {@link #link(String, long, String, Long)} と同じ規則で挿入する（linkのtenantは文書自身のtenant。§11 F-3）。
     * 文書のtenantは変わらないため、lock前の通常読取で得たtenantをanchorの照合に使ってよい。
     *
     * @throws BusinessException 404 文書が無い（論理削除済みを含む）
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public boolean linkExistingDocument(long documentId, String targetType, Long targetId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null || document.getTenantId() == null || document.getTenantId().isBlank()) {
            throw BusinessException.of(404, "error.document.notFound");
        }
        return link(document.getTenantId(), documentId, targetType, targetId);
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
            throw BusinessException.of(409, DELETED_CONFLICT_KEY);
        }
    }
}
