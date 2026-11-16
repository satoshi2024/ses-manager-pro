package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.dsar.PrivacyDsarCommands;
import com.ses.privacy.production.dsar.PrivacyDsarViews;
import com.ses.privacy.production.dsar.PrivacyRequestService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * NF07 DSAR 受付〜SCOPED の API（NF07-A2a、機能スイッチ {@code NF07_DSAR}）。
 *
 * <p>action は {@code ActionPermissionResolver} が GET を {@code privacy.dsar.view}、POST・PUT を {@code privacy.dsar.manage} へ
 * 解決し、privacy-admin だけが許可される。service 層（{@link PrivacyRequestService}）も flag と action を再判定し、
 * 公開スイッチ OFF は {@code FeatureSwitchFilter} と同じ本文の404を返す。subject 参照は要求本文だけで受け取り、URL には
 * request ID と link ID しか置かない（URL は監査・access log に残るため）。承認・package・交付の入口は A2b で足す
 * （{@code /approve}・{@code /package/**}・{@code /export} は handler が無く、OFF なら404、ON でも privacy-admin 以外は403）。
 */
@RestController
@RequestMapping("/api/privacy/dsar")
public class PrivacyDsarApiController {

    private final PrivacyRequestService requestService;

    public PrivacyDsarApiController(PrivacyRequestService requestService) {
        this.requestService = requestService;
    }

    @GetMapping
    public ApiResult<PrivacyDsarViews.Overview> overview(@RequestParam(required = false) String status,
                                                         @RequestParam(defaultValue = "1") long page,
                                                         @RequestParam(defaultValue = "20") long size) {
        return ApiResult.success(requestService.overview(status, page, size));
    }

    @GetMapping("/{id}")
    public ApiResult<PrivacyDsarViews.Detail> detail(@PathVariable long id) {
        return ApiResult.success(requestService.detail(id));
    }

    @PostMapping
    public ApiResult<PrivacyDsarViews.Transition> intake(@RequestBody PrivacyDsarCommands.Intake request) {
        return ApiResult.success(requestService.intake(request));
    }

    @PostMapping("/{id}/identity")
    public ApiResult<PrivacyDsarViews.Transition> verifyIdentity(
            @PathVariable long id, @RequestBody PrivacyDsarCommands.IdentityVerification request) {
        return ApiResult.success(requestService.verifyIdentity(id, request));
    }

    @PostMapping("/{id}/candidates")
    public ApiResult<PrivacyDsarViews.Transition> addCandidate(
            @PathVariable long id, @RequestBody PrivacyDsarCommands.CandidateAddition request) {
        return ApiResult.success(requestService.addCandidate(id, request));
    }

    @PutMapping("/{id}/candidates/{linkId}")
    public ApiResult<PrivacyDsarViews.Transition> resolveCandidate(
            @PathVariable long id, @PathVariable long linkId,
            @RequestBody PrivacyDsarCommands.CandidateResolution request) {
        return ApiResult.success(requestService.resolveCandidate(id, linkId, request));
    }

    @PostMapping("/{id}/scope")
    public ApiResult<PrivacyDsarViews.Transition> scope(
            @PathVariable long id, @RequestBody PrivacyDsarCommands.ScopeConfirmation request) {
        return ApiResult.success(requestService.scope(id, request));
    }

    @PostMapping("/{id}/block")
    public ApiResult<PrivacyDsarViews.Transition> block(
            @PathVariable long id, @RequestBody PrivacyDsarCommands.Closure request) {
        return ApiResult.success(requestService.block(id, request));
    }

    @PostMapping("/{id}/cancel")
    public ApiResult<PrivacyDsarViews.Transition> cancel(
            @PathVariable long id, @RequestBody PrivacyDsarCommands.Closure request) {
        return ApiResult.success(requestService.cancel(id, request));
    }

    /** 公開スイッチ OFF は存在しないURLと同じ404（{@code FeatureSwitchFilter} と同じ本文）。 */
    @ExceptionHandler(PrivacyAccessGuard.PrivacyEntryNotFoundException.class)
    public ResponseEntity<ApiResult<Void>> handleDisabled(PrivacyAccessGuard.PrivacyEntryNotFoundException e) {
        return PrivacyAccessGuard.notFoundResponse();
    }
}
