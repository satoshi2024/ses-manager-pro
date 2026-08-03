package com.ses.dto.document;

import lombok.Builder;
import lombok.Data;

/**
 * 整合性検証結果（verifyIntegrity用）。
 * hash不一致はfindingとして提示するだけで自動修復・自動削除はしない（design §6.3）。
 */
@Data
@Builder
public class IntegrityFinding {

    /** 文書ID */
    private Long documentId;

    /** 版ID */
    private Long versionId;

    /** Storageキー */
    private String storageKey;

    /** DB上のSHA-256 */
    private String expectedSha256;

    /** Storage実体のSHA-256（取得できた場合） */
    private String actualSha256;

    /** 問題種別: HASH_MISMATCH / STORAGE_MISSING / DB_ONLY */
    private String findingType;

    /** 詳細メッセージ */
    private String message;
}
