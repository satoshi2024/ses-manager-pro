package com.ses.service.security;

/**
 * アカウントロック（ログイン失敗回数管理）サービス。
 */
public interface AccountLockService {

    /** ログイン失敗回数の上限（この回数に達するとロック） */
    int MAX_FAILED = 5;

    /** ロック時間（分） */
    int LOCK_MINUTES = 30;

    /**
     * ログイン失敗を記録する。上限到達でアカウントをロックする。
     */
    void onLoginFailure(String username);

    /**
     * ログイン成功時に失敗回数・ロックをリセットする。
     */
    void onLoginSuccess(String username);
}
