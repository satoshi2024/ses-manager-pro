package com.ses.common.exception;

/**
 * 複数ノードからの同時 401 発生時に、他ノードが OAuth トークンリフレッシュを実行中であり、
 * 待機後も完了しなかった場合に送出される例外。
 *
 * 呼び出し元ジョブワーカーはこの例外を検知してジョブを RETRYABLE (next_retry_at = NOW() + 5s) に移行させる。
 * (design.md §4 Multi-node Token Refresh / R4-T02)
 */
public class TokenRefreshInProgressException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public TokenRefreshInProgressException(String message) {
        super(429, message != null ? message : "トークン更新が他ノードで実行中です (TOKEN_REFRESH_IN_PROGRESS)");
    }

    public TokenRefreshInProgressException(Long connectionId) {
        super(429, "トークン更新が他ノードで実行中です (connectionId=" + connectionId + ", TOKEN_REFRESH_IN_PROGRESS)");
    }
}
