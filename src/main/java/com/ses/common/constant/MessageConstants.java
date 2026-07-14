package com.ses.common.constant;

/**
 * メッセージ定数インターフェース
 * システム全体で使用する日本語メッセージを一元管理する
 */
public interface MessageConstants {

    // ========== CRUD操作メッセージ ==========

    /** 保存成功 */
    String SAVE_SUCCESS = "common.msg.saveSuccess";

    /** 更新成功 */
    String UPDATE_SUCCESS = "common.msg.updateSuccess";

    /** 削除成功 */
    String DELETE_SUCCESS = "common.msg.deleteSuccess";

    /** データ未検出 */
    String NOT_FOUND = "error.notFound";

    /** 登録成功 */
    String REGISTER_SUCCESS = "common.msg.registerSuccess";

    // ========== 認証・認可メッセージ ==========

    /** 認証失敗 */
    String AUTH_FAILED = "error.authFailed";

    /** アクセス拒否 */
    String ACCESS_DENIED = "error.accessDenied";

    /** ログイン成功 */
    String LOGIN_SUCCESS = "common.msg.loginSuccess";

    /** ログアウト成功 */
    String LOGOUT_SUCCESS = "common.msg.logoutSuccess";

    /** パスワード不一致 */
    String PASSWORD_MISMATCH = "error.passwordMismatch";

    /** アカウント無効 */
    String ACCOUNT_DISABLED = "error.accountDisabled";

    // ========== システムメッセージ ==========

    /** システムエラー */
    String SYSTEM_ERROR = "error.systemError";

    /** バリデーションエラー */
    String VALIDATION_ERROR = "error.validationError";

    /** 重複エラー */
    String DUPLICATE_ERROR = "error.duplicateError";

    /** 処理中 */
    String PROCESSING = "common.msg.processing";

    // ========== 業務メッセージ ==========

    /** エンジニア登録成功 */
    String ENGINEER_REGISTERED = "common.msg.engineerRegistered";

    /** 案件登録成功 */
    String PROJECT_REGISTERED = "common.msg.projectRegistered";

    /** 提案登録成功 */
    String PROPOSAL_REGISTERED = "common.msg.proposalRegistered";

    /** 契約登録成功 */
    String CONTRACT_REGISTERED = "common.msg.contractRegistered";

    /** ステータス更新成功 */
    String STATUS_UPDATED = "common.msg.statusUpdated";
}








































