package com.ses.privacy.production.retention.hold;

import com.ses.privacy.foundation.JournalAccessDeniedException;

/**
 * 先の append が ACK 不明（commit 済みかもしれない）の後、再送が Journal の権限の拒否で返ったこと（review rb-fe-c P3-N1）。
 *
 * <p>adapter は再送でも {@code pj_head} の更新を command の再生の照合より先に行うため、再送の権限の拒否からは先の試行が効いたかどうかが
 * 分からない。hold の開始・解除はこれを確定の拒否にせず ACK 不明として扱い（pending を残し、reconciler が Journal から判定する）、
 * 監査の ACK 不明に理由 {@value #AUDIT_REASON} を添えて「拒否」と区別する。gate は pending の間 fail-closed のまま。</p>
 */
final class JournalRetryDeniedAfterUnknown extends RuntimeException {

    /** ACK 不明の監査に添える理由。 */
    static final String AUDIT_REASON = "ACCESS_DENIED_AFTER_ACK_UNKNOWN";

    JournalRetryDeniedAfterUnknown(JournalAccessDeniedException cause) {
        super("先の append の ACK が不明な後、再送が権限で拒否されました", cause);
    }
}
