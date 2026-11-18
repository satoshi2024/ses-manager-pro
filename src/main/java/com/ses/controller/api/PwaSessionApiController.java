package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.service.pwa.PwaRestoreAuthorizationService;
import com.ses.service.pwa.PwaUserContextService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 要員PWAのユーザーscope bootstrap。内部user idはクライアントへ返さない。 */
@RestController
@RequiredArgsConstructor
public class PwaSessionApiController {
    private final PwaUserContextService userContextService;

    /**
     * {@code restoreAvailable} は、ロック中の下書きをこのsessionで復元できるか（パスワード再認証に対応するか）。
     * server側のsession認証主体と、serverが立てたsession属性（break-glass・bootstrapの印）だけで判定する
     * （{@code PwaRestoreAuthorizationService#supportsPasswordReauthentication(Authentication, HttpSession)}）。
     * clientは画面表示（復元入口の表示・離脱確認）にだけ使い、認可は復元API側で同じ判定をやり直す。
     */
    @GetMapping("/api/my/session-context")
    public ApiResult<Map<String, Object>> context(
            @RequestHeader(value = "X-User-Scope", required = false) String presentedScope,
            Authentication authentication, HttpServletRequest request) {
        PwaUserContextService.ContextResolution resolution = userContextService.resolve(presentedScope);
        return ApiResult.success(Map.of("userScope", resolution.context().userScope(),
                "preserveQueue", resolution.preserveQueue(),
                "restoreAvailable", PwaRestoreAuthorizationService.supportsPasswordReauthentication(
                        authentication, request.getSession(false))));
    }
}
