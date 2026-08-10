package com.ses.dto.compliance;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * findingの対応操作リクエスト（T065 B2）。
 */
@Data
public class ComplianceFindingActionRequest {

    /** 対応内容・根拠（resolve/exceptionで必須）。 */
    private String note;

    /** 例外承認の有効期限（exceptionで必須・未来日時）。 */
    private LocalDateTime expiresAt;

    /** 根拠文書ID（document archiveのdocument_id、任意）。 */
    private Long evidenceDocumentId;
}
