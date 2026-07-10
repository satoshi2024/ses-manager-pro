package com.ses.common.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 統一APIレスポンスラッパー
 * 全てのAPIレスポンスはこのクラスでラップして返却する
 *
 * @param <T> レスポンスデータの型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 成功ステータスコード */
    public static final int SUCCESS_CODE = 200;

    /** エラーステータスコード */
    public static final int ERROR_CODE = 500;

    /**
     * ステータスコード
     */
    private int code;

    /**
     * メッセージ
     */
    private String message;

    /**
     * レスポンスデータ
     */
    private T data;

    /**
     * 成功レスポンスを生成（データ付き）
     *
     * @param data レスポンスデータ
     * @param <T>  データの型
     * @return 成功レスポンス
     */
    public static <T> ApiResult<T> success(T data) {
        return new ApiResult<>(SUCCESS_CODE, "処理が成功しました", data);
    }

    /**
     * 成功レスポンスを生成（メッセージとデータ付き）
     *
     * @param message メッセージ
     * @param data    レスポンスデータ
     * @param <T>     データの型
     * @return 成功レスポンス
     */
    public static <T> ApiResult<T> success(String message, T data) {
        return new ApiResult<>(SUCCESS_CODE, message, data);
    }

    /**
     * エラーレスポンスを生成（メッセージのみ）
     *
     * @param message エラーメッセージ
     * @param <T>     データの型
     * @return エラーレスポンス
     */
    public static <T> ApiResult<T> error(String message) {
        return new ApiResult<>(ERROR_CODE, message, null);
    }

    /**
     * エラーレスポンスを生成（コードとメッセージ指定）
     *
     * @param code    エラーコード
     * @param message エラーメッセージ
     * @param <T>     データの型
     * @return エラーレスポンス
     */
    public static <T> ApiResult<T> error(int code, String message) {
        return new ApiResult<>(code, message, null);
    }
}
