package com.ses.dto.lifecycle;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ライフサイクルタスク完了コマンド
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompleteLifecycleTaskCommand {

    /**
     * 完了時コメント
     */
    private String completionComment;

    /**
     * 証跡種別 (SELF_DECLARATION, DUAL_CONFIRMATION, DOCUMENT_LINK, SYSTEM_CHECK 等)
     */
    private String evidenceType;

    /**
     * 証跡メタデータJSON
     */
    private String evidenceDataJson;

    /**
     * 紐付ける法定文書台帳ID (DOCUMENT_LINK時)
     */
    private Long documentId;

    /**
     * 文書版ID (任意)
     */
    private Long documentVersionId;

    /**
     * 証跡確認備考
     */
    private String evidenceRemarks;
}
