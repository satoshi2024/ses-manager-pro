package com.ses.common.exception;

import com.ses.common.result.ApiResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.stream.Collectors;

/**
 * グローバル例外ハンドラー
 * REST API（com.ses.controller.api 配下）の例外を ApiResult(JSON) に統一変換する。
 *
 * 対象を api パッケージに限定しているのは、画面（Thymeleaf）コントローラーの例外まで
 * JSON化するとブラウザに生のJSONが表示されてしまうため。画面側の例外は本ハンドラーで
 * 捕捉せず、エラーディスパッチ経由で CustomErrorController がエラーページを描画する。
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.ses.controller.api")
public class GlobalExceptionHandler {

    @Autowired
    private MessageSource messageSource;

    /**
     * 業務例外のハンドリング
     *
     * @param e 業務例外
     * @return エラーレスポンス
     */
    @ExceptionHandler(BusinessException.class)
    public ApiResult<Void> handleBusinessException(BusinessException e) {
        String message = e.getMessage();
        if (e.getMessageKey() != null) {
            message = messageSource.getMessage(e.getMessageKey(), e.getArgs(), e.getMessageKey(), LocaleContextHolder.getLocale());
        }
        log.warn("業務例外が発生しました: code={}, message={}", e.getCode(), message);
        return ApiResult.error(e.getCode(), message);
    }

    /**
     * バリデーション例外のハンドリング
     * リクエストボディのバリデーションエラーを処理する
     *
     * @param e バリデーション例外
     * @return エラーレスポンス（入力エラーの詳細を含む）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResult<Void> handleValidationException(MethodArgumentNotValidException e) {
        BindingResult bindingResult = e.getBindingResult();
        String errorMessage = bindingResult.getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("、"));

        log.warn("入力バリデーションエラー: {}", errorMessage);
        return ApiResult.error(400, "入力内容に誤りがあります：" + errorMessage);
    }

    /**
     * 予期しない例外のハンドリング
     * 全てのキャッチされていない例外を処理する
     *
     * @param e 例外
     * @return エラーレスポンス
     */
    @ExceptionHandler(Exception.class)
    public ApiResult<Void> handleException(Exception e) {
        log.error("システムエラーが発生しました", e);
        return ApiResult.error("システムエラーが発生しました");
    }
}