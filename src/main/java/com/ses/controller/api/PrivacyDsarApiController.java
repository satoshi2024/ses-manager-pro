package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.dsar.PrivacyDsarCommands;
import com.ses.privacy.production.dsar.PrivacyDsarViews;
import com.ses.privacy.production.dsar.PrivacyExportPackageService;
import com.ses.privacy.production.dsar.PrivacyRequestService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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

import java.nio.charset.StandardCharsets;

/**
 * NF07 DSAR の API（受付〜SCOPED は NF07-A2a、検索〜交付記録は NF07-A2b。機能スイッチ {@code NF07_DSAR}）。
 *
 * <p>action は {@code ActionPermissionResolver} が GET を {@code privacy.dsar.view}、POST・PUT を {@code privacy.dsar.manage}、
 * {@code /{id}/approve} を {@code privacy.dsar.approve}、{@code /{id}/package/**} を {@code privacy.dsar.export} へ解決し、
 * privacy-admin だけが許可される。service 層（{@link PrivacyRequestService}・{@link PrivacyExportPackageService}）も flag と action を
 * 再判定し、公開スイッチ OFF は {@code FeatureSwitchFilter} と同じ本文の404を返す。subject 参照は要求本文だけで受け取り、URL には
 * request ID・link ID・package ID しか置かない（URL は監査・access log に残るため）。開示 package は
 * {@code GET /{id}/package/download} だけから取得でき（汎用の {@code /api/files/**} は拒否する）、system から外部へ送る入口は持たない。
 */
@RestController
@RequestMapping("/api/privacy/dsar")
public class PrivacyDsarApiController {

    private final PrivacyRequestService requestService;
    private final PrivacyExportPackageService packageService;

    public PrivacyDsarApiController(PrivacyRequestService requestService, PrivacyExportPackageService packageService) {
        this.requestService = requestService;
        this.packageService = packageService;
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

    // ---------------------------------------------------------------- NF07-A2b

    /** 開示資料の区画（package の要約・要手動確認・hold の状態。記録の値と保存名は返さない）。 */
    @GetMapping("/{id}/delivery")
    public ApiResult<PrivacyDsarViews.DeliverySection> deliverySection(@PathVariable long id) {
        return ApiResult.success(packageService.section(id));
    }

    /** 検索と草案 package の作成（SCOPED → PREVIEWED）。 */
    @PostMapping("/{id}/preview")
    public ApiResult<PrivacyDsarViews.Transition> preview(
            @PathVariable long id, @RequestBody PrivacyDsarCommands.Preview request) {
        return ApiResult.success(packageService.preview(id, request));
    }

    /** 作成者以外の privacy-admin による承認（PREVIEWED → APPROVED）。 */
    @PostMapping("/{id}/approve")
    public ApiResult<PrivacyDsarViews.Transition> approve(
            @PathVariable long id, @RequestBody PrivacyDsarCommands.Approval request) {
        return ApiResult.success(packageService.approve(id, request));
    }

    /** 承認した package の専用ダウンロード（添付。保存しない・推測させない header を付ける）。 */
    @GetMapping("/{id}/package/download")
    public ResponseEntity<byte[]> download(@PathVariable long id, @RequestParam Long packageId) {
        PrivacyDsarViews.PackageDownload download = packageService.download(id, packageId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.filename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(download.content());
    }

    /** system の外での交付の記録（APPROVED → CONFIRMED。package は同時に PURGED）。 */
    @PostMapping("/{id}/delivery")
    public ApiResult<PrivacyDsarViews.Transition> recordDelivery(
            @PathVariable long id, @RequestBody PrivacyDsarCommands.Delivery request) {
        return ApiResult.success(packageService.recordDelivery(id, request));
    }

    /**
     * 承認者による承認前の草案の確認（{@code privacy.dsar.approve}。review P2-3）。保存させない inline の JSON で返し、
     * 作成者・承認待ち以外・hash の不一致は service が拒否する。
     */
    @GetMapping("/{id}/approve/preview")
    public ResponseEntity<byte[]> reviewDraft(@PathVariable long id, @RequestParam Long packageId,
                                              @RequestParam String contentSha256) {
        PrivacyDsarViews.PackageDownload draft = packageService.reviewDraft(id, packageId, contentSha256);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(draft.filename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(draft.content());
    }

    /** 開示以外の請求の手動対応の完了（SCOPED → CONFIRMED。system は処分しない。review P2-2）。 */
    @PostMapping("/{id}/resolution")
    public ApiResult<PrivacyDsarViews.Transition> recordManualResolution(
            @PathVariable long id, @RequestBody PrivacyDsarCommands.ManualResolution request) {
        return ApiResult.success(requestService.recordManualResolution(id, request));
    }

    /** 公開スイッチ OFF は存在しないURLと同じ404（{@code FeatureSwitchFilter} と同じ本文）。 */
    @ExceptionHandler(PrivacyAccessGuard.PrivacyEntryNotFoundException.class)
    public ResponseEntity<ApiResult<Void>> handleDisabled(PrivacyAccessGuard.PrivacyEntryNotFoundException e) {
        return PrivacyAccessGuard.notFoundResponse();
    }
}
