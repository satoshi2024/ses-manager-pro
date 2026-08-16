package com.ses.service.portal;

/**
 * portal APIのrate limit（R4.5: login/招待/download/upload/検収APIに適用）。
 * インメモリのスライディングウィンドウ（単一インスタンス運用のため十分。
 * 複数インスタンス展開時は共有storeへ置き換えること）。
 */
public interface PortalRateLimiter {

    /**
     * キー（ip / userId+操作）の1分あたり許容回数を超えていなければtrue。
     * perMinuteが0以下の場合は無制限。
     */
    boolean tryAcquire(String key, int perMinute);
}
