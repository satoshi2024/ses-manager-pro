package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.pwa.PwaRestoreConfirmRequest;
import com.ses.dto.pwa.PwaRestorePermitResponse;
import com.ses.dto.pwa.PwaRestoreProofResponse;
import com.ses.dto.pwa.PwaRestoreReauthenticateRequest;
import com.ses.service.pwa.PwaRestoreAuthorizationService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 要員PWAの明示復元に限った再認証・単回proof API。 */
@RestController
@RequestMapping("/api/my/pwa/restore")
@RequiredArgsConstructor
public class MyPwaRestoreApiController {
    private final PwaRestoreAuthorizationService authorizationService;

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
}
