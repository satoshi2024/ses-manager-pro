package com.ses.controller.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ses.common.result.ApiResult;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.retention.policy.PrivacyPolicyActivationRequest;
import com.ses.privacy.production.retention.policy.PrivacyPolicyDetail;
import com.ses.privacy.production.retention.policy.PrivacyPolicyExplanation;
import com.ses.privacy.production.retention.policy.PrivacyPolicyExplanationService;
import com.ses.privacy.production.retention.policy.PrivacyPolicyRegistrationRequest;
import com.ses.privacy.production.retention.policy.PrivacyPolicyRegistrationResult;
import com.ses.privacy.production.retention.policy.PrivacyPolicyRegistrationService;
import com.ses.privacy.production.retention.policy.PrivacyPolicySummary;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * NF07 policy API（W3-14 の説明API と NF07-REG の登録・有効化。機能スイッチ {@code NF07_RETENTION}）。
 *
 * <ul>
 *   <li>{@code GET /api/privacy/policies}: 登録済み policy の一覧（登録情報だけ。承認の証明ではない）</li>
 *   <li>{@code GET /api/privacy/policies/{id}}: 登録情報と、その key に対する説明照会の結果</li>
 *   <li>{@code GET /api/privacy/policies/evaluate}: key を指定した説明照会</li>
 *   <li>{@code POST /api/privacy/policies}: 署名済み envelope の登録（verify-then-store。NF07-REG）</li>
 *   <li>{@code POST /api/privacy/policies/{id}/activate}: 登録者と別の privacy-admin による current の切替（NF07-REG）</li>
 * </ul>
 * 照会の action は {@code privacy.policy.view}、登録・有効化は {@code privacy.policy.manage}（どちらも privacy-admin だけが
 * 許可）。公開スイッチが OFF なら {@code FeatureSwitchFilter} と service 層の {@link PrivacyAccessGuard} がどちらも同じ本文の
 * 404を返す。登録・有効化は D-01・D-02・D-03 の署名と trust anchor の登録が揃うまで、service が存在しない URL と同じ404を返す。
 * 説明照会は policy が未知・未承認・検証不能なら {@code APPROVED_ACTIVE} 以外を返し（fail-closed）、実行認可は付与しない。
 */
@RestController
@RequestMapping("/api/privacy/policies")
public class PrivacyPolicyApiController {

    private final PrivacyPolicyExplanationService explanationService;
    private final PrivacyPolicyRegistrationService registrationService;

    public PrivacyPolicyApiController(PrivacyPolicyExplanationService explanationService,
                                      PrivacyPolicyRegistrationService registrationService) {
        this.explanationService = explanationService;
        this.registrationService = registrationService;
    }

    @GetMapping
    public ApiResult<IPage<PrivacyPolicySummary>> list(@RequestParam(defaultValue = "1") long current,
                                                       @RequestParam(defaultValue = "10") long size) {
        return ApiResult.success(explanationService.list(current, size));
    }

    @GetMapping("/{id:\\d+}")
    public ApiResult<PrivacyPolicyDetail> detail(@PathVariable long id) {
        return ApiResult.success(explanationService.detail(id));
    }

    @GetMapping("/evaluate")
    public ApiResult<PrivacyPolicyExplanation> evaluate(@RequestParam(required = false) String moduleCode,
                                                        @RequestParam(required = false) String dataElementId,
                                                        @RequestParam(required = false) String targetAction,
                                                        @RequestParam(required = false) String targetProvider) {
        return ApiResult.success(explanationService.explain(moduleCode, dataElementId, targetAction, targetProvider));
    }

    @PostMapping
    public ApiResult<PrivacyPolicyRegistrationResult> register(@RequestBody PrivacyPolicyRegistrationRequest request) {
        return ApiResult.success(registrationService.register(request));
    }

    @PostMapping("/{id:\\d+}/activate")
    public ApiResult<PrivacyPolicyRegistrationResult> activate(
            @PathVariable long id, @RequestBody(required = false) PrivacyPolicyActivationRequest request) {
        return ApiResult.success(registrationService.activate(id, request));
    }

    /** 公開スイッチ OFF・登録の開放の前は存在しないURLと同じ404（{@code FeatureSwitchFilter} と同じ本文）。 */
    @ExceptionHandler(PrivacyAccessGuard.PrivacyEntryNotFoundException.class)
    public ResponseEntity<ApiResult<Void>> handleDisabled(PrivacyAccessGuard.PrivacyEntryNotFoundException e) {
        return PrivacyAccessGuard.notFoundResponse();
    }
}
