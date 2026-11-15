package com.ses.service.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Document;
import com.ses.entity.DocumentDisposalRequest;
import com.ses.mapper.DocumentDisposalRequestMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.service.security.epoch.AuthorizationEpochAnchor;
import com.ses.service.security.epoch.AuthorizationEpochFence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 文書の利用可否を変える状態変化（法的hold・廃棄申請・廃棄・状態）の書込みを集約する
 * （NF01細粒度epoch計画 §2.2 document行、§4.3 F3、§4.5 U4c）。
 *
 * <p>各writerは文書行を先にX lockし（lock順 §3.1 の 15 DOCUMENT → 18 DOCUMENT_DISPOSAL_REQUEST）、変化があった時だけ
 * 同じtransactionで文書の証跡利用可否epochを進める（E-1、E-2、E-4）。旧値はlock取得後のCAS条件またはlocking readで
 * 再確認するため、呼出元がlock前に読んだsnapshotが古くても増分を漏らさない。</p>
 *
 * <ul>
 *   <li>増分する: 法的holdの設定・解除、廃棄申請の登録・承認・却下・執行（DISPOSED）。</li>
 *   <li>増分しない: DRAFT→CONFIRMED、CONFIRMED→AMENDED（利用可否の状態集合 {DRAFT, CONFIRMED, AMENDED} の内側の遷移）、
 *       廃棄執行後のstorage削除失敗によるFAILED記録（DISPOSEDは終端で不適格のまま）。</li>
 *   <li>anchorのtenantは文書行自身のtenantを使う。保存期限jobのようにtenant contextの無い経路でも、
 *       {@code requireTenantContext()} に依存しない。</li>
 * </ul>
 *
 * <p>{@code t_document} の状態列・{@code t_document_disposal_request} の更新は、本classと
 * {@code DocumentMetadataCommitService}（版追加時のAMENDED）だけに置く（{@code DocumentEligibilityWriterFenceTest}）。</p>
 */
@Slf4j
@Service
public class DocumentEligibilityService {

    static final String STATUS_DRAFT = "DRAFT";
    static final String STATUS_CONFIRMED = "CONFIRMED";
    static final String STATUS_AMENDED = "AMENDED";
    static final String STATUS_DISPOSED = "DISPOSED";
    static final String REQUEST_PENDING = "PENDING";
    static final String REQUEST_APPROVED = "APPROVED";
    static final String REQUEST_REJECTED = "REJECTED";
    static final String REQUEST_DISPOSED = "DISPOSED";
    static final String REQUEST_FAILED = "FAILED";
    /** 保存期限jobが登録する廃棄申請の申請者（システム自動抽出）。 */
    static final long SYSTEM_REQUESTER = -1L;
    static final String RETENTION_REASON = "法定保存年数経過による自動廃棄候補抽出";
    private static final List<String> DISPOSABLE_STATUSES = List.of(STATUS_CONFIRMED, STATUS_AMENDED, "CANCELLED");

    private final DocumentMapper documentMapper;
    private final DocumentDisposalRequestMapper disposalRequestMapper;
    private final AuthorizationEpochFence fence;

    public DocumentEligibilityService(DocumentMapper documentMapper,
                                      DocumentDisposalRequestMapper disposalRequestMapper,
                                      AuthorizationEpochFence fence) {
        this.documentMapper = documentMapper;
        this.disposalRequestMapper = disposalRequestMapper;
        this.fence = fence;
    }

    /**
     * 法的holdを設定・解除する（設定・解除とも増分）。
     *
     * @param snapshot 呼出元が読んだ文書行（tenant・ID・version・hold flag）
     * @return 今回変更してepochを進めた場合 true、既に同じ値で何もしなかった場合 false
     * @throws BusinessException 409 snapshotの後に文書が更新された（楽観lock）
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public boolean changeLegalHold(Document snapshot, boolean hold) {
        AuthorizationEpochAnchor.Document anchor = anchorOf(snapshot);
        fence.lockForUpdate(anchor);
        int oldFlag = flag(snapshot.getLegalHoldFlag());
        int newFlag = hold ? 1 : 0;
        if (oldFlag == newFlag) {
            return false;
        }
        long version = requireVersion(snapshot);
        // X lock保持下のUPDATEは最新行を見る。旧hold値もCAS条件に含め、実際に変わった時だけ増分する。
        int updated = documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, anchor.documentId())
                .eq(Document::getTenantId, anchor.tenantId())
                .eq(Document::getVersion, version)
                .eq(Document::getLegalHoldFlag, oldFlag)
                .set(Document::getLegalHoldFlag, newFlag)
                .set(Document::getVersion, version + 1));
        if (updated == 0) {
            throw BusinessException.of(409, "error.document.optimisticLock");
        }
        fence.advance(anchor);
        return true;
    }

    /**
     * 廃棄申請（PENDING）を登録する。lock取得後に文書行を読み直し、hold・保存期限・状態を再確認する。
     *
     * @throws BusinessException 404 文書が無い、400 hold中・保存期限未確定・廃棄できない状態
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public DocumentDisposalRequest requestDisposal(String tenantId, long documentId, Long requestedBy, String reason) {
        AuthorizationEpochAnchor.Document anchor = AuthorizationEpochAnchor.document(tenantId, documentId);
        fence.lockForUpdate(anchor);
        Document current = selectForUpdate(anchor);
        if (current == null) {
            throw BusinessException.of(404, "error.document.notFound");
        }
        if (flag(current.getLegalHoldFlag()) == 1) {
            throw BusinessException.of(400, "error.document.legalHoldActive");
        }
        if (current.getRetentionUntil() == null) {
            throw BusinessException.of(400, "error.document.retentionUndetermined");
        }
        if (current.getStatus() == null || !DISPOSABLE_STATUSES.contains(current.getStatus())) {
            throw BusinessException.of(400, "error.document.notDisposable");
        }
        DocumentDisposalRequest request = new DocumentDisposalRequest();
        request.setDocumentId(documentId);
        request.setRequestedBy(requestedBy);
        request.setStatus(REQUEST_PENDING);
        request.setReason(reason);
        disposalRequestMapper.insert(request);
        fence.advance(anchor);
        return request;
    }

    /**
     * 廃棄申請を承認する（PENDING→APPROVED）。利用可否は不適格のままだが、廃棄申請の状態変化として増分する（§2.2）。
     *
     * @throws BusinessException 409 申請が既にPENDINGでない（同時更新）
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void approveDisposal(String tenantId, long documentId, long requestId, Long approvedBy) {
        AuthorizationEpochAnchor.Document anchor = AuthorizationEpochAnchor.document(tenantId, documentId);
        fence.lockForUpdate(anchor);
        int updated = disposalRequestMapper.update(null, new LambdaUpdateWrapper<DocumentDisposalRequest>()
                .eq(DocumentDisposalRequest::getId, requestId)
                .eq(DocumentDisposalRequest::getDocumentId, documentId)
                .eq(DocumentDisposalRequest::getStatus, REQUEST_PENDING)
                .set(DocumentDisposalRequest::getStatus, REQUEST_APPROVED)
                .set(DocumentDisposalRequest::getApprovedBy, approvedBy)
                .set(DocumentDisposalRequest::getApprovedAt, LocalDateTime.now()));
        if (updated == 0) {
            throw BusinessException.of(409, "error.document.disposalConcurrentUpdate");
        }
        fence.advance(anchor);
    }

    /**
     * 廃棄申請を却下する（PENDING→REJECTED）。却下だけが残る文書は再び適格になり得るため増分する。
     *
     * @param reasonText 却下理由を含めた保存用の理由文（呼出元が組み立てる）
     * @throws BusinessException 409 申請が既にPENDINGでない（同時更新）
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void rejectDisposal(String tenantId, long documentId, long requestId, Long rejectedBy, String reasonText) {
        AuthorizationEpochAnchor.Document anchor = AuthorizationEpochAnchor.document(tenantId, documentId);
        fence.lockForUpdate(anchor);
        int updated = disposalRequestMapper.update(null, new LambdaUpdateWrapper<DocumentDisposalRequest>()
                .eq(DocumentDisposalRequest::getId, requestId)
                .eq(DocumentDisposalRequest::getDocumentId, documentId)
                .eq(DocumentDisposalRequest::getStatus, REQUEST_PENDING)
                .set(DocumentDisposalRequest::getStatus, REQUEST_REJECTED)
                .set(DocumentDisposalRequest::getReason, reasonText)
                .set(DocumentDisposalRequest::getApprovedBy, rejectedBy)
                .set(DocumentDisposalRequest::getApprovedAt, LocalDateTime.now()));
        if (updated == 0) {
            throw BusinessException.of(409, "error.document.disposalConcurrentUpdate");
        }
        fence.advance(anchor);
    }

    /**
     * 承認済みの廃棄申請を執行し、文書をDISPOSEDにする。storageの物理削除は呼出元がcommit後に行う。
     *
     * @return 今回DISPOSEDにした場合 true、既にDISPOSEDで何もしなかった場合 false
     * @throws BusinessException 404 文書が無い、400 hold中、409 申請が既にAPPROVEDでない（同時更新）
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public boolean executeDisposal(String tenantId, long documentId, long requestId) {
        AuthorizationEpochAnchor.Document anchor = AuthorizationEpochAnchor.document(tenantId, documentId);
        fence.lockForUpdate(anchor);
        Document current = selectForUpdate(anchor);
        if (current == null) {
            throw BusinessException.of(404, "error.document.notFound");
        }
        if (flag(current.getLegalHoldFlag()) == 1) {
            throw BusinessException.of(400, "error.document.legalHoldActive");
        }
        if (STATUS_DISPOSED.equals(current.getStatus())) {
            return false;
        }
        documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, anchor.documentId())
                .eq(Document::getTenantId, anchor.tenantId())
                .eq(Document::getLegalHoldFlag, 0)
                .ne(Document::getStatus, STATUS_DISPOSED)
                .set(Document::getStatus, STATUS_DISPOSED));
        int updated = disposalRequestMapper.update(null, new LambdaUpdateWrapper<DocumentDisposalRequest>()
                .eq(DocumentDisposalRequest::getId, requestId)
                .eq(DocumentDisposalRequest::getDocumentId, documentId)
                .eq(DocumentDisposalRequest::getStatus, REQUEST_APPROVED)
                .set(DocumentDisposalRequest::getStatus, REQUEST_DISPOSED)
                .set(DocumentDisposalRequest::getDisposedAt, LocalDateTime.now()));
        if (updated == 0) {
            throw BusinessException.of(409, "error.document.disposalConcurrentUpdate");
        }
        fence.advance(anchor);
        return true;
    }

    /**
     * 廃棄執行のcommit後にstorage削除が失敗したことを記録する（DISPOSED→FAILED）。文書はDISPOSEDの終端で不適格のまま
     * なのでepochは進めない（§4.3 executeDisposal）。afterCommitから呼ばれるため、元のtransactionに参加せず
     * 独立したtransactionで確定させる。DISPOSEDの申請だけを書き換える（状態CAS）。
     *
     * @return 書き換えた行数（申請がDISPOSEDでなければ0）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int markDisposalStorageFailed(long requestId, String reasonText) {
        return disposalRequestMapper.update(null, new LambdaUpdateWrapper<DocumentDisposalRequest>()
                .eq(DocumentDisposalRequest::getId, requestId)
                .eq(DocumentDisposalRequest::getStatus, REQUEST_DISPOSED)
                .set(DocumentDisposalRequest::getStatus, REQUEST_FAILED)
                .set(DocumentDisposalRequest::getReason, reasonText));
    }

    /**
     * 文書を確定する（DRAFT→CONFIRMED）。利用可否の状態集合の内側の遷移なので増分しないが、文書行のX lockで
     * 証跡readerと直列化する。
     *
     * @throws BusinessException 409 snapshotの後に文書が更新された、またはDRAFTでなくなった
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void confirm(Document snapshot, LocalDate retentionUntil) {
        AuthorizationEpochAnchor.Document anchor = anchorOf(snapshot);
        fence.lockForUpdate(anchor);
        long version = requireVersion(snapshot);
        int updated = documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, anchor.documentId())
                .eq(Document::getTenantId, anchor.tenantId())
                .eq(Document::getVersion, version)
                .eq(Document::getStatus, STATUS_DRAFT)
                .set(Document::getStatus, STATUS_CONFIRMED)
                .set(Document::getRetentionUntil, retentionUntil)
                .set(Document::getVersion, version + 1));
        if (updated == 0) {
            throw BusinessException.of(409, "error.document.optimisticLock");
        }
    }

    /**
     * 版追加に伴い確定済み文書をAMENDEDにする（増分しない）。Spring経路の版追加は
     * {@code DocumentMetadataCommitService.commitVersion} が同じ規則で行い、本メソッドは直接生成された旧test adapter
     * （{@code DocumentServiceImpl} の metadata commit service 未注入時）だけが使う。
     *
     * @return AMENDEDにした場合 true、CONFIRMEDでなかったため何もしなかった場合 false
     * @throws BusinessException 409 snapshotの後に文書が更新された
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public boolean markAmended(Document snapshot) {
        AuthorizationEpochAnchor.Document anchor = anchorOf(snapshot);
        fence.lockForUpdate(anchor);
        if (!STATUS_CONFIRMED.equals(snapshot.getStatus())) {
            return false;
        }
        long version = requireVersion(snapshot);
        int updated = documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, anchor.documentId())
                .eq(Document::getTenantId, anchor.tenantId())
                .eq(Document::getVersion, version)
                .set(Document::getStatus, STATUS_AMENDED)
                .set(Document::getVersion, version + 1));
        if (updated == 0) {
            throw BusinessException.of(409, "error.document.optimisticLock");
        }
        return true;
    }

    /**
     * 保存期限jobの廃棄候補（PENDING、申請者=システム）を1文書について登録する。jobはtransactionを持たず文書ごとに
     * 本メソッドを呼ぶため、文書ごとの短いtransactionになる（§4.3 DocumentRetentionJob）。
     *
     * <p>tenantは文書行自身のtenantを引数で受け取り、tenant contextに依存しない（W3-10 Review §6 W3-17）。lock取得後に
     * 文書行を読み直して候補条件（CONFIRMED・hold無し・保存期限経過）を再確認し、承認待ち・承認済みの申請が無い時だけ
     * 登録して増分する。</p>
     *
     * @return 今回登録してepochを進めた場合 true、条件を満たさない・既に申請がある場合 false
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public boolean registerRetentionDisposalCandidate(String tenantId, long documentId, LocalDate today) {
        Objects.requireNonNull(today, "today");
        AuthorizationEpochAnchor.Document anchor = AuthorizationEpochAnchor.document(tenantId, documentId);
        fence.lockForUpdate(anchor);
        Document current = selectForUpdate(anchor);
        if (current == null || !STATUS_CONFIRMED.equals(current.getStatus()) || flag(current.getLegalHoldFlag()) != 0
                || current.getRetentionUntil() == null || current.getRetentionUntil().isAfter(today)) {
            return false;
        }
        Long open = disposalRequestMapper.selectCount(new LambdaQueryWrapper<DocumentDisposalRequest>()
                .eq(DocumentDisposalRequest::getDocumentId, documentId)
                .in(DocumentDisposalRequest::getStatus, REQUEST_PENDING, REQUEST_APPROVED));
        if (open != null && open > 0) {
            return false;
        }
        DocumentDisposalRequest request = new DocumentDisposalRequest();
        request.setDocumentId(documentId);
        request.setRequestedBy(SYSTEM_REQUESTER);
        request.setReason(RETENTION_REASON);
        request.setStatus(REQUEST_PENDING);
        request.setCreatedAt(LocalDateTime.now());
        disposalRequestMapper.insert(request);
        fence.advance(anchor);
        return true;
    }

    /** lock取得後に文書行を最新の値で読む（RRでも古いsnapshotを読まないようlocking readにする）。 */
    private Document selectForUpdate(AuthorizationEpochAnchor.Document anchor) {
        return documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getId, anchor.documentId())
                .eq(Document::getTenantId, anchor.tenantId())
                .last("FOR UPDATE"));
    }

    private static AuthorizationEpochAnchor.Document anchorOf(Document snapshot) {
        Objects.requireNonNull(snapshot, "document");
        if (snapshot.getId() == null) {
            throw new IllegalArgumentException("文書IDが必要です");
        }
        return AuthorizationEpochAnchor.document(snapshot.getTenantId(), snapshot.getId());
    }

    private static long requireVersion(Document snapshot) {
        if (snapshot.getVersion() == null) {
            // 楽観lockの基準が無い更新はCASにならないため受け付けない。
            throw BusinessException.of(409, "error.document.optimisticLock");
        }
        return snapshot.getVersion();
    }

    private static int flag(Integer value) {
        return value == null ? 0 : value;
    }
}
