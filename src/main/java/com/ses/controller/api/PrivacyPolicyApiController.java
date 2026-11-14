package com.ses.controller.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ses.common.result.ApiResult;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.retention.policy.PrivacyPolicyDetail;
import com.ses.privacy.production.retention.policy.PrivacyPolicyExplanation;
import com.ses.privacy.production.retention.policy.PrivacyPolicyExplanationService;
import com.ses.privacy.production.retention.policy.PrivacyPolicySummary;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * NF07 policy 説明API（読み取り専用。W3-14、機能スイッチ {@code NF07_RETENTION}）。
 *
 * <ul>
 *   <li>{@code GET /api/privacy/policies}: 登録済み policy の一覧（登録情報だけ。承認の証明ではない）</li>
 *   <li>{@code GET /api/privacy/policies/{id}}: 登録情報と、その key に対する説明照会の結果</li>
 *   <li>{@code GET /api/privacy/policies/evaluate}: key を指定した説明照会</li>
 * </ul>
 * action は全て {@code privacy.policy.view}（privacy-admin だけが許可）。公開スイッチが OFF なら
 * {@code FeatureSwitchFilter} と service 層の {@link PrivacyAccessGuard} がどちらも同じ本文の404を返す。
 * 説明照会は policy が未知・未承認・検証不能なら {@code APPROVED_ACTIVE} 以外を返し（fail-closed）、
 * 実行認可は付与しない。書き込みの入口は持たない（policy の登録・有効化は署名後の NF07-REG）。
 */
@RestController
@RequestMapping("/api/privacy/policies")
public class PrivacyPolicyApiController {

    private final PrivacyPolicyExplanationService explanationService;

    public PrivacyPolicyApiController(PrivacyPolicyExplanationService explanationService) {
        this.explanationService = explanationService;
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

    /** 公開スイッチ OFF は存在しないURLと同じ404（{@code FeatureSwitchFilter} と同じ本文）。 */
    @ExceptionHandler(PrivacyAccessGuard.PrivacyEntryNotFoundException.class)
    public ResponseEntity<ApiResult<Void>> handleDisabled(PrivacyAccessGuard.PrivacyEntryNotFoundException e) {
        return PrivacyAccessGuard.notFoundResponse();
    }
}
