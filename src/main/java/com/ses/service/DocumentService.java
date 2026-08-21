package com.ses.service;

import com.ses.dto.document.DocumentRegisterRequest;
import com.ses.dto.document.IntegrityFinding;
import com.ses.entity.Document;
import com.ses.entity.DocumentVersion;

import java.io.InputStream;
import java.util.List;

/**
 * 文書台帳サービス。
 *
 * <h3>設計上の不変条件（design §6.3）</h3>
 * <ul>
 *   <li>冪等キー {@code (source_type, business_key, version_discriminator)} で重複登録を防ぐ。</li>
 *   <li>storage put成功後にDB commitが失敗した場合、storageはorphanとして残す。
 *       補償削除は {@code cleanup-safety-hours} 経過後（即削除しない）。</li>
 *   <li>verifyIntegrity はread-onlyで、hash不一致時に自動修復・自動削除をしない。</li>
 *   <li>外部API（CloudSign等）はDB transaction外で呼ぶ（platform-invariants §3.3）。</li>
 * </ul>
 */
public interface DocumentService {

    /**
     * 生成文書（PDF等）をアーカイブに登録する。
     * 保存順: quarantine put → scan → hash検証 → DB tx metadata → promote（design §2）。
     *
     * @param request   登録リクエスト
     * @param content   バイナリコンテンツ
     * @return 登録された文書エンティティ
     */
    Document registerGenerated(DocumentRegisterRequest request, InputStream content);

    /**
     * 受領文書をアーカイブに登録する。
     * {@link #registerGenerated} と同じフローで保存する。
     *
     * @param request   登録リクエスト
     * @param content   バイナリコンテンツ
     * @return 登録された文書エンティティ
     */
    Document registerReceived(DocumentRegisterRequest request, InputStream content);

    /**
     * 既存文書に新しい版を追加する（差替・訂正）。
     * 旧版は削除しない。CONFIRMED状態の文書に対して差替を行うと状態がAMENDEDに遷移する。
     *
     * @param documentId   文書ID
     * @param request      差替リクエスト（changeReason必須）
     * @param content      バイナリコンテンツ
     * @return 追加された文書版エンティティ
     */
    DocumentVersion addVersion(Long documentId, DocumentRegisterRequest request, InputStream content);

    /**
     * 文書と業務エンティティを関連付ける。
     *
     * @param documentId   文書ID
     * @param targetType   リンク先種別（CUSTOMER/CONTRACT/INVOICE等）
     * @param targetId     リンク先ID
     */
    void link(Long documentId, String targetType, Long targetId);

    /**
     * 文書に法的holdを設定または解除する。
     * hold中は廃棄申請を受け付けない（R4.2）。
     *
     * @param documentId   文書ID
     * @param hold         true=hold設定, false=hold解除
     * @param reason       操作理由（監査ログへ記録）
     */
    void placeLegalHold(Long documentId, boolean hold, String reason);

    /**
     * 廃棄申請を作成する。
     * legal_hold_flag=1の文書への申請は BusinessException で拒否する（R4.2）。
     * retention_until が NULL の文書への申請も拒否する（design §6.1）。
     * 廃棄承認は管理者固定（design §6.2の逸脱）。
     *
     * @param documentId 文書ID
     * @param reason     廃棄理由
     * @return 廃棄申請エンティティ
     */
    com.ses.entity.DocumentDisposalRequest requestDisposal(Long documentId, String reason);

    /**
     * 廃棄申請を承認する（申請者と異なる管理者のみ実行可能）。
     *
     * @param disposalRequestId 廃棄申請ID
     */
    void approveDisposal(Long disposalRequestId);

    /**
     * 廃棄申請を却下する。
     *
     * @param disposalRequestId 廃棄申請ID
     * @param reason            却下理由
     */
    void rejectDisposal(Long disposalRequestId, String reason);

    /**
     * 承認済み廃棄申請に基づいてstorage削除・廃棄証跡記録を実行する。
     * storage削除失敗時はステータスをFAILEDとし、廃棄証跡に失敗理由を記録する（R4.3）。
     *
     * @param disposalRequestId 廃棄申請ID
     */
    void executeDisposal(Long disposalRequestId);

    /**
     * DBのhashとStorage実体のhashを比較して整合性を検証する（read-only）。
     * hash不一致時に自動修復・自動削除をしない（design §6.3）。
     *
     * @param documentId 検証対象文書ID
     * @return 整合性問題のfinding一覧（正常の場合は空リスト）
     */
    List<IntegrityFinding> verifyIntegrity(Long documentId);

    /**
     * 文書をDRAFTからCONFIRMEDに確定する。
     * 確定時に {@code retention_until} を算出・固定する。
     *
     * @param documentId 文書ID
     */
    void confirm(Long documentId);

    /**
     * 条件付き文書台帳検索（ページネーション・認可母集団適用）。
     */
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.ses.dto.document.DocumentListDTO> searchDocuments(com.ses.dto.document.DocumentSearchQuery query);

    /**
     * 文書詳細取得（版履歴・リンク一覧を含む）。
     */
    com.ses.dto.document.DocumentDetailDTO getDocumentDetail(Long documentId);

    /**
     * 最新または指定版のファイルをダウンロード用ストリームとして開く。
     *
     * @param documentId 文書ID
     * @param versionNo  版番号（nullの場合は最新版）
     * @return 入力ストリーム
     */
    InputStream download(Long documentId, Integer versionNo);

    /**
     * 指定版の storage_key を DB から返す（APIレスポンスでは null 化するが、
     * FileScope の assertDownloadAllowed には実キーが必要）。
     *
     * @return 版が無ければ null
     */
    String getVersionStorageKey(Long documentId, Integer versionNo);

    /**
     * DataScope フィルタをクエリラッパーに適用する。
     */
    void applyDataScopeFilter(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.ses.entity.Document> wrapper);
}
