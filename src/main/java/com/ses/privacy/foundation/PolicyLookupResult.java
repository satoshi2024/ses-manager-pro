package com.ses.privacy.foundation;

/**
 * ポリシーソース検索結果レコード。
 */
record PolicyLookupResult(
    LookupStatus status,
    PolicyApprovalEnvelope currentEnvelope,
    String currentRevision,
    String currentDigest
) {
    enum LookupStatus {
        CURRENT_CANDIDATE,  // 承認済みエンベロープを特定
        UNAPPROVED,         // 下書きまたは未承認状態
        NOT_FOUND,          // 当該ポリシーが存在しない
        STORE_UNAVAILABLE   // ストア照会例外
    }
}
