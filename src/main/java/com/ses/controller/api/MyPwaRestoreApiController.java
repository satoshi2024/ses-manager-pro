package com.ses.controller.api;

import com.ses.common.exception.SafeErrorPolicy;
import com.ses.common.result.ApiResult;
import com.ses.common.util.CorrelationContext;
import com.ses.dto.pwa.PwaRestoreConfirmRequest;
import com.ses.dto.pwa.PwaRestorePermitResponse;
import com.ses.dto.pwa.PwaRestoreProofResponse;
import com.ses.dto.pwa.PwaRestoreReauthenticateRequest;
import com.ses.service.pwa.PwaRestoreAuthorizationService;
import com.ses.service.pwa.PwaRestoreReauthUnsupportedException;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 要員PWAの明示復元に限った再認証・単回proof API。 */
@Slf4j
@RestController
@RequestMapping("/api/my/pwa/restore")
@RequiredArgsConstructor
public class MyPwaRestoreApiController {
    /** 機械可読のerror codeを返すheader（{@code PersistentSessionFilter} と同じ名前）。 */
    static final String ERROR_CODE_HEADER = "X-SES-Error-Code";

    private final PwaRestoreAuthorizationService authorizationService;
    private final MessageSource messageSource;

    @PostMapping("/reauthenticate")
    public ApiResult<PwaRestoreProofResponse> reauthenticate(@RequestBody PwaRestoreReauthenticateRequest request,
                                                              HttpSession session,
                                                              Authentication authentication) {
        return ApiResult.success(authorizationService.reauthenticate(request, session, authentication));
    }

    @PostMapping("/confirm")
    public ApiResult<PwaRestorePermitResponse> confirm(@RequestBody PwaRestoreConfirmRequest request,
                                                        HttpSession session,
                                                        Authentication authentication) {
        return ApiResult.success(authorizationService.confirm(request, session, authentication));
    }

    /**
     * パスワード再認証に対応しないsession（OIDC等）からの復元要求（NF04 P1-N4-1 方案A）。
     * 通常の拒否（{@code error.pwa.restore.rejected}）と区別できるよう、403に専用のmessage keyと
     * {@code data.type}・{@value #ERROR_CODE_HEADER} を付ける。パスワードやscope等の入力値は応答・ログに出さない。
     */
    @ExceptionHandler(PwaRestoreReauthUnsupportedException.class)
    public ResponseEntity<ApiResult<Map<String, String>>> reauthUnsupported(PwaRestoreReauthUnsupportedException e) {
        CorrelationContext.put(CorrelationContext.ERROR_CODE, PwaRestoreReauthUnsupportedException.ERROR_CODE);
        CorrelationContext.put(CorrelationContext.ERROR_CATEGORY, "BUSINESS");
        log.warn("PWA下書き復元を拒否しました（パスワード再認証に対応しないsession）: errorCode={}",
                PwaRestoreReauthUnsupportedException.ERROR_CODE);
        String message = SafeErrorPolicy.resolveMessageKey(PwaRestoreReauthUnsupportedException.MESSAGE_KEY,
                messageSource, LocaleContextHolder.getLocale());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .header(ERROR_CODE_HEADER, PwaRestoreReauthUnsupportedException.ERROR_CODE)
                .body(new ApiResult<>(403, message,
                        Map.of("type", PwaRestoreReauthUnsupportedException.ERROR_CODE)));
    }
}
