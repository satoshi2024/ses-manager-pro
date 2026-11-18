package com.ses.service.integrationhub;

import com.ses.entity.integrationhub.ApiNonceReplay;

import java.time.LocalDateTime;

/** NF-05 nonce replay service。raw nonceはhash算出後に保存しない。 */
public interface ApiNonceReplayService {
    /** 署名timestampの許容差（±秒）。認証filterの受付窓とnonce保存期限は必ずこの値から導く。 */
    long SIGNED_TIMESTAMP_SKEW_SECONDS = 300;

    /**
     * nonce行の保存秒数（N5-P3-1）。基準は max(受付時刻, 署名時刻)。
     *
     * <p>受付窓は秒切捨てで最長 署名時刻 + 許容差 + 1秒弱まで開いている。保存期限が窓とちょうど重なると、
     * purge直後の同一requestを再受付できてしまう。さらにpurgeを実行するnodeの時計が受付nodeより進んでいる
     * 場合も考え、許容差の2倍 + 1秒を保存する。
     */
    long NONCE_RETENTION_SECONDS = 2 * SIGNED_TIMESTAMP_SKEW_SECONDS + 1;

    boolean accept(String clientId, int credentialVersion, byte[] rawNonce,
                   LocalDateTime signedTimestamp, LocalDateTime acceptedAt);

    int purgeExpired(LocalDateTime serverNow, int maxRows);
}
