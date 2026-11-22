package com.ses.service.security;

import com.ses.dto.security.SessionSummary;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;

import java.util.List;

/** DBに失効状態を持つsession metadataサービス。 */
public interface PersistentSessionService {

    String TRACKED_ATTRIBUTE = "SES_SESSION_TRACKED";
    String SESSION_HASH_ATTRIBUTE = "SES_SESSION_HASH";

    void register(HttpServletRequest request, Authentication authentication);

    boolean validateAndTouch(HttpServletRequest request, Authentication authentication);

    List<SessionSummary> list(Authentication authentication, HttpServletRequest request);

    void revoke(Long sessionId, Long userId, String currentHash, String reason);

    void revokeOthers(Long userId, String currentHash, String reason);

    void revokeAllForUser(Long userId, String reason);

    /** logout処理でHTTP session破棄前に現在行を失効する。 */
    void revokeCurrent(HttpServletRequest request, Authentication authentication, String reason);

    /**
     * HTTP session の属性「backup の静止中の最終利用」（RB-QUIESCE U-14。design.md §6.4）。静止中に通した要求の時刻を書き、
     * 解除の後の最初の要求の idle の判定に使ってから消す。
     */
    String QUIESCE_LAST_USE_ATTRIBUTE = "backupQuiesceLastUse";

    /**
     * backup の書込み静止の間の logout（U-8）で、解除の後に失効を書くための値を logout の時点で取っておく（DB へは書かない）。
     * 失効させる行が無い（永続 session が無効・未追跡の session・利用者が不明）なら null。
     */
    default CapturedRevocation captureCurrent(HttpServletRequest request, Authentication authentication, String reason) {
        return null;
    }

    /** {@link #captureCurrent} で取っておいた値で、現在行を logout の時点の時刻で失効する（解除後の書込み）。 */
    default void revokeCaptured(CapturedRevocation captured) {
        throw new UnsupportedOperationException("取っておいた値での失効に対応していません");
    }

    /** 解除の後に書く失効の値（session ID の生の値と IP は持たない）。 */
    record CapturedRevocation(String tenantId, String sessionHash, Long userId, java.time.LocalDateTime revokedAt,
                              String reason) {

        /** log へ出す要約（user ID・session hash の先頭 8 文字・時刻）。 */
        public String summary() {
            String hash = sessionHash == null ? "-" : sessionHash.substring(0, Math.min(8, sessionHash.length()));
            return "logout の失効（user=" + userId + "、session=" + hash + "、時刻=" + revokedAt + "）";
        }
    }
}
