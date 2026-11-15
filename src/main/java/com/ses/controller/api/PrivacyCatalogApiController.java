package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.PrivacyCoverageReport;
import com.ses.privacy.production.retention.catalog.PrivacyCatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * NF07 data element catalog API（読み取り専用。F2、機能スイッチ {@code NF07_RETENTION}）。
 *
 * <p>{@code GET /api/privacy/catalog}: 登録済み provider（列 allow-list・本人の導出規則）と、catalog の data element を
 * 登録済み／未登録（理由付き、{@code PROVIDER_UNREGISTERED} 等）に分けて返す。action は {@code privacy.catalog.view}
 * （privacy-admin だけが許可）。公開スイッチが OFF なら {@code FeatureSwitchFilter} と service 層の
 * {@link PrivacyAccessGuard} がどちらも同じ本文の404を返す。書き込みの入口は持たない（catalog の変更は migration と
 * Plan Review だけで行う）。
 */
@RestController
@RequestMapping("/api/privacy/catalog")
public class PrivacyCatalogApiController {

    private final PrivacyCatalogService catalogService;

    public PrivacyCatalogApiController(PrivacyCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    public ApiResult<PrivacyCoverageReport> catalog() {
        return ApiResult.success(catalogService.catalog());
    }

    /** 公開スイッチ OFF は存在しないURLと同じ404（{@code FeatureSwitchFilter} と同じ本文）。 */
    @ExceptionHandler(PrivacyAccessGuard.PrivacyEntryNotFoundException.class)
    public ResponseEntity<ApiResult<Void>> handleDisabled(PrivacyAccessGuard.PrivacyEntryNotFoundException e) {
        return PrivacyAccessGuard.notFoundResponse();
    }
}
