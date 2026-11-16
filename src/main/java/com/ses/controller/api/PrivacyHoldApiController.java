package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.retention.hold.PrivacyHoldOverview;
import com.ses.privacy.production.retention.hold.PrivacyHoldService;
import com.ses.privacy.production.retention.hold.PrivacyHoldStartRequest;
import com.ses.privacy.production.retention.hold.PrivacyHoldStartResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * NF07 保全 hold API（NF07-A1H-1、機能スイッチ {@code NF07_RETENTION}）。
 *
 * <p>{@code GET /api/privacy/holds}（{@code privacy.hold.view}）: hold の一覧、tenant の gate 状態、開始の前提（D-05・
 * 許可種別・時計・Journal）、SUBJECT hold で守られない element と tenant 内件数。
 * {@code POST /api/privacy/holds}（{@code privacy.hold.manage}）: hold の開始（Journal の ACK を取ってから projection を書く）。
 * どちらも privacy-admin だけが許可され、公開スイッチ OFF は {@code FeatureSwitchFilter} と service 層の
 * {@link PrivacyAccessGuard} がどちらも同じ本文の404を返す。解除の入口は持たない（NF07-REL。
 * {@code POST /api/privacy/holds/{id}/release} は handler が無く 404）。subject 参照は要求本文だけで受け取る。
 */
@RestController
@RequestMapping("/api/privacy/holds")
public class PrivacyHoldApiController {

    private final PrivacyHoldService holdService;

    public PrivacyHoldApiController(PrivacyHoldService holdService) {
        this.holdService = holdService;
    }

    @GetMapping
    public ApiResult<PrivacyHoldOverview> overview() {
        return ApiResult.success(holdService.overview());
    }

    @PostMapping
    public ApiResult<PrivacyHoldStartResult> start(@RequestBody PrivacyHoldStartRequest request) {
        return ApiResult.success(holdService.start(request));
    }

    /** 公開スイッチ OFF は存在しないURLと同じ404（{@code FeatureSwitchFilter} と同じ本文）。 */
    @ExceptionHandler(PrivacyAccessGuard.PrivacyEntryNotFoundException.class)
    public ResponseEntity<ApiResult<Void>> handleDisabled(PrivacyAccessGuard.PrivacyEntryNotFoundException e) {
        return PrivacyAccessGuard.notFoundResponse();
    }
}
