package com.ses.common.exception;

import com.ses.common.result.ApiResult;
import com.ses.common.util.CorrelationContext;
import com.ses.common.util.LogRedaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;


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
    public ResponseEntity<ApiResult<Void>> handleBusinessException(BusinessException e) {
        int code = SafeErrorPolicy.safeHttpCode(e == null ? 500 : e.getCode());
        String message = SafeErrorPolicy.resolveBusinessMessage(e, messageSource, LocaleContextHolder.getLocale());
        // 相関IDのない単体呼出しでは、前回処理のMDC値を引き継がない。
        boolean requestContext = CorrelationContext.get(CorrelationContext.CORRELATION_ID) != null;
        String existingErrorCode = requestContext
                ? CorrelationContext.get(CorrelationContext.ERROR_CODE) : null;
        String errorCode = existingErrorCode == null
                ? "BUSINESS_EXCEPTION"
                : SafeErrorPolicy.safeErrorCode(existingErrorCode);
        String existingCategory = requestContext
                ? CorrelationContext.get(CorrelationContext.ERROR_CATEGORY) : null;
        String category = code >= 500 || "SYSTEM".equals(existingCategory) ? "SYSTEM" : "BUSINESS";
        CorrelationContext.put(CorrelationContext.ERROR_CODE, errorCode);
        CorrelationContext.put(CorrelationContext.ERROR_CATEGORY, category);
        log.warn("業務例外: httpCode={} category={} errorCode={} exceptionClass={} {}",
                code, category, errorCode, LogRedaction.exceptionType(e), contextFields());
        return ResponseEntity.status(toHttpStatus(code)).body(ApiResult.<Void>error(code, message));
    }

    /** PWAの競合はclient/server差分をdataへ返し、クライアントがlast-write-winsを選べないようにする。 */
    @ExceptionHandler(PwaConflictException.class)
    public ResponseEntity<ApiResult<Object>> handlePwaConflict(PwaConflictException e) {
        CorrelationContext.put(CorrelationContext.ERROR_CODE, "OPTIMISTIC_LOCK_CONFLICT");
        CorrelationContext.put(CorrelationContext.ERROR_CATEGORY, "BUSINESS");
        log.warn("PWA command競合: category=BUSINESS exceptionClass={} {}",
                LogRedaction.exceptionType(e), contextFields());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiResult<>(409, "他の操作と競合しました。再読み込みして再試行してください。", e.getData()));
    }

    /**
     * バリデーション例外のハンドリング
     * リクエストボディのバリデーションエラーを処理する
     *
     * @param e バリデーション例外
     * @return エラーレスポンス（入力エラーの詳細を含む）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Void>> handleValidationException(MethodArgumentNotValidException e) {
        BindingResult bindingResult = e.getBindingResult();
        CorrelationContext.put(CorrelationContext.ERROR_CODE, "VALIDATION_FAILED");
        CorrelationContext.put(CorrelationContext.ERROR_CATEGORY, "BUSINESS");
        log.warn("入力バリデーションエラー: fieldCount={} {}",
                bindingResult == null ? 0 : bindingResult.getErrorCount(), contextFields());
        String message = bindingResult == null ? "入力内容に誤りがあります。"
                : bindingResult.getAllErrors().stream()
                .map(org.springframework.validation.ObjectError::getDefaultMessage)
                .filter(SafeErrorPolicy::isAllowedFixedMessage)
                .findFirst()
                .orElse("入力内容に誤りがあります。");
        return ResponseEntity.badRequest().body(ApiResult.<Void>error(400, message));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiResult<Void>> handleAccessDeniedException(org.springframework.security.access.AccessDeniedException e) {
        String message = SafeErrorPolicy.resolveMessageKey("error.accessDenied", messageSource, LocaleContextHolder.getLocale());
        CorrelationContext.put(CorrelationContext.ERROR_CODE, "ACCESS_DENIED");
        CorrelationContext.put(CorrelationContext.ERROR_CATEGORY, "BUSINESS");
        log.warn("アクセス拒否エラー: category=BUSINESS exceptionClass={} {}",
                LogRedaction.exceptionType(e), contextFields());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResult.<Void>error(403, message));
    }

    /**
     * 予期しない例外のハンドリング
     * 全てのキャッチされていない例外を処理する
     *
     * @param e 例外
     * @return エラーレスポンス
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleException(Exception e) {
        CorrelationContext.put(CorrelationContext.ERROR_CODE, "SYSTEM_UNEXPECTED");
        CorrelationContext.put(CorrelationContext.ERROR_CATEGORY, "SYSTEM");
        log.error("システムエラー: category=SYSTEM exceptionClass={} detail={} {}",
                LogRedaction.exceptionType(e), LogRedaction.safeThrowableSummary(e), contextFields());
        return ResponseEntity.internalServerError().body(ApiResult.<Void>error(500, SafeErrorPolicy.DEFAULT_SYSTEM_MESSAGE));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class, java.time.format.DateTimeParseException.class})
    public ResponseEntity<ApiResult<Void>> handleRequestParameterException(Exception e) {
        CorrelationContext.put(CorrelationContext.ERROR_CODE, "INVALID_REQUEST_PARAMETER");
        CorrelationContext.put(CorrelationContext.ERROR_CATEGORY, "BUSINESS");
        log.warn("リクエストパラメータ不正: category=BUSINESS exceptionClass={} {}",
                LogRedaction.exceptionType(e), contextFields());
        return ResponseEntity.badRequest().body(ApiResult.<Void>error(400, "リクエストパラメータが不正です"));
    }

    /**
     * リクエストボディが壊れている・型が合わない・そもそも無い場合。
     * これはクライアント側の誤りなので 400 を返す。
     * ハンドラが無いと汎用 Exception ハンドラに落ちて 500「システムエラー」となり、
     * 利用者には原因不明のエラーが出るうえ、監視上も本物の障害と区別できなくなる。
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResult<Void>> handleUnreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException e) {
        CorrelationContext.put(CorrelationContext.ERROR_CODE, "INVALID_REQUEST_BODY");
        CorrelationContext.put(CorrelationContext.ERROR_CATEGORY, "BUSINESS");
        log.warn("リクエストボディを解釈できませんでした: category=BUSINESS exceptionClass={} {}",
                LogRedaction.exceptionType(e), contextFields());
        return ResponseEntity.badRequest().body(ApiResult.<Void>error(400, "リクエスト内容が不正です"));
    }

    private String contextFields() {
        return "correlationId=" + safeContext(CorrelationContext.CORRELATION_ID)
                + " invoiceId=" + safeContext(CorrelationContext.INVOICE_ID)
                + " digitalInvoiceId=" + safeContext(CorrelationContext.DIGITAL_INVOICE_ID)
                + " jobId=" + safeContext(CorrelationContext.JOB_ID)
                + " providerOperationId=" + safeContext(CorrelationContext.PROVIDER_OPERATION_ID)
                + " errorCode=" + safeContext(CorrelationContext.ERROR_CODE)
                + " errorCategory=" + safeContext(CorrelationContext.ERROR_CATEGORY);
    }

    private String safeContext(String key) {
        String value = CorrelationContext.get(key);
        return value == null ? "-" : value;
    }

    private HttpStatus toHttpStatus(int code) {
        return switch (code) {
            case 400 -> HttpStatus.BAD_REQUEST;
            // 401はSpring Securityのセッション期限切れと衝突しやすい。
            // freee tokenError等の業務コードは呼び出し側で400へ寄せる（HFP-01-BUG-04）。
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 409 -> HttpStatus.CONFLICT;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            case 502 -> HttpStatus.BAD_GATEWAY;
            case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
