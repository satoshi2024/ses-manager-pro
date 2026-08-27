package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.service.pwa.PwaUserContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 要員PWAのユーザーscope bootstrap。内部user idはクライアントへ返さない。 */
@RestController
@RequiredArgsConstructor
public class PwaSessionApiController {
    private final PwaUserContextService userContextService;

    @GetMapping("/api/my/session-context")
    public ApiResult<Map<String, Object>> context(
            @RequestHeader(value = "X-User-Scope", required = false) String presentedScope) {
        PwaUserContextService.ContextResolution resolution = userContextService.resolve(presentedScope);
        return ApiResult.success(Map.of("userScope", resolution.context().userScope(),
                "preserveQueue", resolution.preserveQueue()));
    }
}
