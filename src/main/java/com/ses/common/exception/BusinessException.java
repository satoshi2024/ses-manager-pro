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

    /**
     * エラーメッセージのみ指定するコンストラクタ
     * デフォルトのエラーコード500を使用
     *
     * @param message エラーメッセージ
     */
    public BusinessException(String message) {
        super(message);
        this.code = 500;
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
    }
}
