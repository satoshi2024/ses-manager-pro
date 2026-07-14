package com.ses.common.exception;

import lombok.Getter;

/**
 * 業務例外クラス
 * ビジネスロジック上のエラーを表現する実行時例外
 */
@Getter
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * エラーコード
     */
    private final int code;
    private final String messageKey;
    private final Object[] args;

    /**
     * エラーメッセージのみ指定するコンストラクタ
     * デフォルトのエラーコード500を使用
     *
     * @param message エラーメッセージ
     */
    public BusinessException(String message) {
        super(message);
        this.code = 500;
        this.messageKey = null;
        this.args = null;
    }

    /**
     * エラーコードとメッセージを指定するコンストラクタ
     *
     * @param code    エラーコード
     * @param message エラーメッセージ
     */
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
        this.messageKey = null;
        this.args = null;
    }

    private BusinessException(int code, String messageKey, Object[] args) {
        super(messageKey); // For stacktrace
        this.code = code;
        this.messageKey = messageKey;
        this.args = args;
    }

    public static BusinessException of(String messageKey, Object... args) {
        return new BusinessException(500, messageKey, args);
    }

    public static BusinessException of(int code, String messageKey, Object... args) {
        return new BusinessException(code, messageKey, args);
    }
}