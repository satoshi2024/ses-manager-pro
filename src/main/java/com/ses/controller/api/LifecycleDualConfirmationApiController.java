package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.lifecycle.dual.DualConfirmationCandidateDto;
import com.ses.dto.lifecycle.dual.DualConfirmationDecisionRequest;
import com.ses.dto.lifecycle.dual.DualConfirmationResultDto;
import com.ses.dto.lifecycle.dual.DualConfirmationSubmitRequest;
import com.ses.dto.lifecycle.dual.DualConfirmationViewDto;
import com.ses.service.lifecycle.dual.DualConfirmationChannel;
import com.ses.service.lifecycle.dual.LifecycleDualConfirmationCoordinator;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 二者確認（DUAL_CONFIRMATION）のAPI（NF01細粒度epoch計画 §5.2 A1〜A7、W3-21a）。
 *
 * <ul>
 *   <li>社内の入口 {@code /api/lifecycle/tasks/{id}/dual-confirmation/**}（lifecycle menu、action {@code lifecycle.*}）と、
 *       要員本人の入口 {@code /api/my/lifecycle/tasks/{id}/dual-confirmation/**}（myLifecycle menu、action {@code my.*}）。
 *       既存のAPI rootに属するため、権限のseedとbaseline + denyの規則は変わらない。</li>
 *   <li>本人の入口には確認・差戻しのrouteを作らない（A4は社内の入口だけ）。</li>
 *   <li>本controllerはtransactionを持たず（{@code ControllerTransactionalBanTest}）、principalのユーザーIDとsessionのtenantを
 *       coordinatorへ渡すだけである。actor・tenant・role・hash等をbodyから受け取らない（未知propertyはcoordinatorが400）。</li>
 *   <li>{@code Idempotency-Key} headerは提出・確認で必須（形式はcoordinatorが検査し、不正・欠落は400）。</li>
 *   <li>子スイッチ {@code app.features.nf01-dual-confirmation.enabled} がOFFなら全routeが400
 *       {@code error.lifecycle.dualConfirmationDisabled}。親のNF-01がOFFならfilterが404にする。</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class LifecycleDualConfirmationApiController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final LifecycleDualConfirmationCoordinator coordinator;

    // ===== 社内（/api/lifecycle） =====

    /** A1: 現在のprojection・提出の要約・操作可否。 */
    @GetMapping("/api/lifecycle/tasks/{id}/dual-confirmation")
    public ApiResult<DualConfirmationViewDto> view(@PathVariable("id") long taskId) {
        return ApiResult.success(coordinator.view(taskId, principal(), DualConfirmationChannel.INTERNAL));
    }

    /** A2: 証跡候補（適格なSKILL_SHEETのCLEAN版）。 */
    @GetMapping("/api/lifecycle/tasks/{id}/dual-confirmation/candidates")
    public ApiResult<List<DualConfirmationCandidateDto>> candidates(@PathVariable("id") long taskId) {
        return ApiResult.success(coordinator.candidates(taskId, principal(), DualConfirmationChannel.INTERNAL));
    }

    /** A3: 提出。 */
    @PostMapping("/api/lifecycle/tasks/{id}/dual-confirmation/submissions")
    public ApiResult<DualConfirmationResultDto> submit(
            @PathVariable("id") long taskId,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) DualConfirmationSubmitRequest body) {
        return ApiResult.success(coordinator.submit(taskId, principal(), DualConfirmationChannel.INTERNAL, body,
                idempotencyKey));
    }

    /** A4: 確認または差戻し（管理者・HR）。 */
    @PostMapping("/api/lifecycle/tasks/{id}/dual-confirmation/submissions/{submissionId}/decision")
    public ApiResult<DualConfirmationResultDto> decide(
            @PathVariable("id") long taskId,
            @PathVariable("submissionId") long submissionId,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) DualConfirmationDecisionRequest body) {
        return ApiResult.success(coordinator.decide(taskId, submissionId, principal(), body, idempotencyKey));
    }

    // ===== 要員本人（/api/my/lifecycle）。確認routeは作らない =====

    /** A5: A1の本人版（本人公開のタスクだけ）。 */
    @GetMapping("/api/my/lifecycle/tasks/{id}/dual-confirmation")
    public ApiResult<DualConfirmationViewDto> myView(@PathVariable("id") long taskId) {
        return ApiResult.success(coordinator.view(taskId, principal(), DualConfirmationChannel.SELF));
    }

    /** A6: A2の本人版。 */
    @GetMapping("/api/my/lifecycle/tasks/{id}/dual-confirmation/candidates")
    public ApiResult<List<DualConfirmationCandidateDto>> myCandidates(@PathVariable("id") long taskId) {
        return ApiResult.success(coordinator.candidates(taskId, principal(), DualConfirmationChannel.SELF));
    }

    /** A7: 本人の提出。 */
    @PostMapping("/api/my/lifecycle/tasks/{id}/dual-confirmation/submissions")
    public ApiResult<DualConfirmationResultDto> mySubmit(
            @PathVariable("id") long taskId,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) DualConfirmationSubmitRequest body) {
        return ApiResult.success(coordinator.submit(taskId, principal(), DualConfirmationChannel.SELF, body,
                idempotencyKey));
    }

    private static LifecycleDualConfirmationCoordinator.Principal principal() {
        return new LifecycleDualConfirmationCoordinator.Principal(SecurityUtils.currentUserId(),
                SecurityUtils.currentTenantId());
    }
}
