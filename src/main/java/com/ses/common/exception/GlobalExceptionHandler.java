package com.ses.common.exception;

import com.ses.backup.quiesce.BackupQuiesceRejectionRecorder;
import com.ses.backup.quiesce.BackupQuiesceRejections;
import com.ses.backup.quiesce.BackupWriteGate;
import com.ses.backup.quiesce.BackupWriteQuiescedException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.CorrelationContext;
import com.ses.common.util.LogRedaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.PessimisticLockingFailureException;
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

    /** backup の書込み静止の gate（RB-QUIESCE §6.4）。無い context では静止による 503 の変換をしない。 */
    private BackupWriteGate backupWriteGate;

    @Autowired(required = false)
    public void setBackupWriteGate(BackupWriteGate backupWriteGate) {
        this.backupWriteGate = backupWriteGate;
    }

    /** 静止による拒否の数え方と読取り専用の拒否の WARN の間引き（filter と共有。無い context では数えずに WARN を出す）。 */
    private BackupQuiesceRejectionRecorder rejectionRecorder;

    @Autowired(required = false)
    public void setBackupQuiesceRejectionRecorder(BackupQuiesceRejectionRecorder rejectionRecorder) {
        this.rejectionRecorder = rejectionRecorder;
    }

    /** 今の要求の method と path（log 用。個人データを含めない形）。要求の外なら {@code -}。 */
    private static String currentRoute() {
        if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes attributes) {
            jakarta.servlet.http.HttpServletRequest request = attributes.getRequest();
            return request.getMethod() + " "
                    + com.ses.common.util.RequestPaths.forAudit(com.ses.common.util.RequestPaths.pathWithinApplication(request));
        }
        return "-";
    }

    /**
     * backup の書込み静止の間に permit なしで書こうとした（必須の監査・uploads の変更等）。再試行できる 503 で返す
     * （design.md §6.10。{@code Retry-After}・{@code X-SES-Error-Code: BACKUP_QUIESCED}）。
     */
    @ExceptionHandler(BackupWriteQuiescedException.class)
    public ResponseEntity<ApiResult<Void>> handleBackupWriteQuiesced(BackupWriteQuiescedException e) {
        return quiescedResponse(e);
    }

    /**
     * 静止による拒否（{@link BackupWriteQuiescedException}、gate が書込みを許さない間の読取り専用の拒否（server の 1792 か
     * Connector/J の client 側の S1009））を 503 の応答にする。
     * 静止による拒否でなければ null。業務例外・行 lock の競合・予期しない例外の handler の前で呼ぶ（最後の防線の変換）。
     */
    private ResponseEntity<ApiResult<Void>> quiescedResponseIfApplicable(Throwable e) {
        return BackupQuiesceRejections.isQuiesceRejection(e, backupWriteGate) ? quiescedResponse(e) : null;
    }

    private ResponseEntity<ApiResult<Void>> quiescedResponse(Throwable e) {
        CorrelationContext.put(CorrelationContext.ERROR_CODE, BackupQuiesceRejections.ERROR_CODE);
        CorrelationContext.put(CorrelationContext.ERROR_CATEGORY, "SYSTEM");
        // controller の中の拒否は filter まで届かないため、ここで filter と同じ部品で数えて読取り専用の拒否の WARN を間引く
        // （rejected_requests{readonly_sql|uploads|api}。2b review P2-1。§13.1〜§13.3）
        BackupQuiesceRejectionRecorder recorder = rejectionRecorder;
        if (recorder != null) {
            recorder.record(e, false, true, currentRoute() + " " + contextFields(), "api-handler");
        } else if (BackupQuiesceRejections.findQuiescedException(e) == null) {
            log.warn("errorCode=BACKUP_QUIESCE_READONLY_REJECTED backup の静止中に読取り専用の接続へ書こうとした要求を 503 に"
                    + "しました: exceptionClass={} {}", LogRedaction.exceptionType(e), contextFields());
        } else if (log.isDebugEnabled()) {
            log.debug("backup の静止中のため書込みを 503 にしました: exceptionClass={} {}", LogRedaction.exceptionType(e),
                    contextFields());
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(BackupQuiesceRejections.RETRY_AFTER_HEADER,
                        Integer.toString(BackupQuiesceRejections.retryAfterSeconds(backupWriteGate)))
                .header(BackupQuiesceRejections.ERROR_CODE_HEADER, BackupQuiesceRejections.ERROR_CODE)
                .body(ApiResult.<Void>error(503,
                        BackupQuiesceRejections.writeUnavailableMessage(messageSource, LocaleContextHolder.getLocale())));
    }

    /**
     * 業務例外のハンドリング
     *
     * @param e 業務例外
     * @return エラーレスポンス
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResult<Void>> handleBusinessException(BusinessException e) {
        ResponseEntity<ApiResult<Void>> quiesced = quiescedResponseIfApplicable(e);
        if (quiesced != null) {
            return quiesced;
        }
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
                .body(new ApiResult<>(409, SafeErrorPolicy.statusMessage(409, messageSource, LocaleContextHolder.getLocale()),
                        e.getData()));
    }

    /**
     * 行lockの競合（MySQL 1213 deadlock・1205 lock待ちtimeout。Springでは {@link PessimisticLockingFailureException} の
     * 派生 {@code DeadlockLoserDataAccessException}・{@code CannotAcquireLockException}）。
     *
     * <p>transactionはDBまたは宣言的transactionでrollbackされ、業務上の決定でもないため、500ではなく再読み込みして再試行できる
     * 409として返す（NF01細粒度epoch計画 §9 RR-9、W3-16b）。lock順の不備を監視で見つけられるよう、errorCodeは
     * {@code LOCK_CONFLICT}・分類はSYSTEMとし、例外の型だけをWARNで記録する（SQLや鍵値は出さない）。</p>
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ApiResult<Void>> handleLockConflict(PessimisticLockingFailureException e) {
        ResponseEntity<ApiResult<Void>> quiesced = quiescedResponseIfApplicable(e);
        if (quiesced != null) {
            return quiesced;
        }
        CorrelationContext.put(CorrelationContext.ERROR_CODE, "LOCK_CONFLICT");
        CorrelationContext.put(CorrelationContext.ERROR_CATEGORY, "SYSTEM");
        log.warn("行lockの競合により処理を中断しました（再試行可能）: category=SYSTEM exceptionClass={} {}",
                LogRedaction.exceptionType(e), contextFields());
        String message = SafeErrorPolicy.resolveBusinessMessage(
                BusinessException.of(409, "error.common.optimisticLock"), messageSource, LocaleContextHolder.getLocale());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResult.<Void>error(409, message));
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
        String invalidInput = SafeErrorPolicy.localizedMessage("error.request.invalidInput", "入力内容に誤りがあります。",
                messageSource, LocaleContextHolder.getLocale());
        String message = bindingResult == null ? invalidInput
                : bindingResult.getAllErrors().stream()
                .map(org.springframework.validation.ObjectError::getDefaultMessage)
                .filter(SafeErrorPolicy::isAllowedFixedMessage)
                .findFirst()
                .orElse(invalidInput);
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
        ResponseEntity<ApiResult<Void>> quiesced = quiescedResponseIfApplicable(e);
        if (quiesced != null) {
            return quiesced;
        }
        CorrelationContext.put(CorrelationContext.ERROR_CODE, "SYSTEM_UNEXPECTED");
        CorrelationContext.put(CorrelationContext.ERROR_CATEGORY, "SYSTEM");
        log.error("システムエラー: category=SYSTEM exceptionClass={} detail={} {}",
                LogRedaction.exceptionType(e), LogRedaction.safeThrowableSummary(e), contextFields());
        return ResponseEntity.internalServerError().body(ApiResult.<Void>error(500,
                SafeErrorPolicy.statusMessage(500, messageSource, LocaleContextHolder.getLocale())));
    }

    /**
     * api controller の {@code @RequestBody} 等が受け付けない Content-Type。クライアント側の誤りなので
     * 汎用 Exception ハンドラ（500・SYSTEM）へ落とさず415で返す（RB-NF02 review P3-2）。文言は業務例外の415
     * （P3-N2-1）と同じstatus別の文言（利用者のlocale。RB-I18N）にし、受け付ける形式は Accept header で示す。
     */
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResult<Void>> handleUnsupportedMediaType(
            org.springframework.web.HttpMediaTypeNotSupportedException e) {
        CorrelationContext.put(CorrelationContext.ERROR_CODE, "UNSUPPORTED_MEDIA_TYPE");
        CorrelationContext.put(CorrelationContext.ERROR_CATEGORY, "BUSINESS");
        log.warn("受け付けないContent-Type: category=BUSINESS exceptionClass={} {}",
                LogRedaction.exceptionType(e), contextFields());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        if (e.getSupportedMediaTypes() != null && !e.getSupportedMediaTypes().isEmpty()) {
            response.header(org.springframework.http.HttpHeaders.ACCEPT,
                    org.springframework.http.MediaType.toString(e.getSupportedMediaTypes()));
        }
        return response.body(ApiResult.<Void>error(415,
                SafeErrorPolicy.statusMessage(415, messageSource, LocaleContextHolder.getLocale())));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class, java.time.format.DateTimeParseException.class})
    public ResponseEntity<ApiResult<Void>> handleRequestParameterException(Exception e) {
        CorrelationContext.put(CorrelationContext.ERROR_CODE, "INVALID_REQUEST_PARAMETER");
        CorrelationContext.put(CorrelationContext.ERROR_CATEGORY, "BUSINESS");
        log.warn("リクエストパラメータ不正: category=BUSINESS exceptionClass={} {}",
                LogRedaction.exceptionType(e), contextFields());
        return ResponseEntity.badRequest().body(ApiResult.<Void>error(400, SafeErrorPolicy.localizedMessage(
                "error.request.invalidParameter", "リクエストパラメータが不正です", messageSource, LocaleContextHolder.getLocale())));
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
        return ResponseEntity.badRequest().body(ApiResult.<Void>error(400, SafeErrorPolicy.localizedMessage(
                "error.request.invalidBody", "リクエスト内容が不正です", messageSource, LocaleContextHolder.getLocale())));
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
            // SafeErrorPolicy.safeHttpCodeが許可する413（上限超過）をstatus行でも413として返す。
            case 413 -> HttpStatus.PAYLOAD_TOO_LARGE;
            // 同じく許可済みの415（添付できないファイル形式。NF-02 P3-N2-1）。
            case 415 -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            case 502 -> HttpStatus.BAD_GATEWAY;
            case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
