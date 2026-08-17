package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.changerequest.EngineerChangeRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 要員ポータル（プロフィール/スキルシート）API。
 * 本人scopeはengineer-account linkから解決し、リクエストにengineerIdを受け取らない。
 * 金額（原価/commission）は本人レスポンスへ一切含めない（R1.4）。
 */
@RestController
@RequestMapping("/api/my/profile")
@RequiredArgsConstructor
public class MyProfileApiController {

    private final EngineerAccountLinkService linkService;
    private final EngineerChangeRequestService changeRequestService;

    private Long currentEngineerId() {
        Long engineerId = linkService.findEngineerIdByUserId(SecurityUtils.currentUserId());
        if (engineerId == null) {
            throw BusinessException.of(403, "error.my.notLinked");
        }
        return engineerId;
    }

    @GetMapping
    public ApiResult<EngineerChangeRequestService.MyProfileView> profile() {
        return ApiResult.success(changeRequestService.myProfile(currentEngineerId()));
    }

    @GetMapping("/skill-options")
    public ApiResult<java.util.List<EngineerChangeRequestService.SkillOptionDto>> skillOptions() {
        return ApiResult.success(changeRequestService.listSkillOptions());
    }

    @GetMapping("/skill-sheet")
    public ApiResult<EngineerChangeRequestService.SkillSheetPreview> skillSheetPreview() {
        return ApiResult.success(changeRequestService.skillSheetPreview(currentEngineerId()));
    }

    /** previewで表示したfingerprintを確認済みとして固定する（R1.3）。staleは409。 */
    @PostMapping("/skill-sheet/confirm")
    public ApiResult<EngineerChangeRequestService.SkillSheetConfirmResult> confirm(@RequestBody ConfirmRequest request) {
        String fingerprint = request == null ? null : request.getFingerprint();
        return ApiResult.success(changeRequestService.confirmSkillSheet(currentEngineerId(), fingerprint));
    }

    public static class ConfirmRequest {
        private String fingerprint;

        public String getFingerprint() {
            return fingerprint;
        }

        public void setFingerprint(String fingerprint) {
            this.fingerprint = fingerprint;
        }
    }
}
