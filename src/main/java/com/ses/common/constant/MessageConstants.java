package com.ses.common.constant;

/**
 * メッセージ定数インターフェース
 * システム全体で使用する日本語メッセージを一元管理する
 */
public interface MessageConstants {

    // ========== CRUD操作メッセージ ==========

    /** 保存成功 */
    String SAVE_SUCCESS = "保存しました";

    /** 更新成功 */
    String UPDATE_SUCCESS = "更新しました";

    /** 削除成功 */
    String DELETE_SUCCESS = "削除しました";

    /** データ未検出 */
    String NOT_FOUND = "データが見つかりません";

    /** 登録成功 */
    String REGISTER_SUCCESS = "登録しました";

    // ========== 認証・認可メッセージ ==========

    /** 認証失敗 */
    String AUTH_FAILED = "認証に失敗しました";

    /** アクセス拒否 */
    String ACCESS_DENIED = "アクセス権限がありません";

    /** ログイン成功 */
    String LOGIN_SUCCESS = "ログインしました";

    /** ログアウト成功 */
    String LOGOUT_SUCCESS = "ログアウトしました";

    /** パスワード不一致 */
    String PASSWORD_MISMATCH = "パスワードが一致しません";

    /** アカウント無効 */
    String ACCOUNT_DISABLED = "アカウントが無効です";

    // ========== システムメッセージ ==========

    /** システムエラー */
    String SYSTEM_ERROR = "システムエラーが発生しました";

    /** バリデーションエラー */
    String VALIDATION_ERROR = "入力内容に誤りがあります";

    /** 重複エラー */
    String DUPLICATE_ERROR = "既に登録されています";

    /** 処理中 */
    String PROCESSING = "処理中です。しばらくお待ちください";

    // ========== 業務メッセージ ==========

    /** エンジニア登録成功 */
    String ENGINEER_REGISTERED = "エンジニアを登録しました";

    /** 案件登録成功 */
    String PROJECT_REGISTERED = "案件を登録しました";

    /** 提案登録成功 */
    String PROPOSAL_REGISTERED = "提案を登録しました";

    /** 契約登録成功 */
    String CONTRACT_REGISTERED = "契約を登録しました";

    /** ステータス更新成功 */
    String STATUS_UPDATED = "ステータスを更新しました";
}
