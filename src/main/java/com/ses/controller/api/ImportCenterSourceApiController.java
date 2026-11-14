package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.importcenter.foundation.ImportEntity;
import com.ses.importcenter.metadata.ImportCatalogPage;
import com.ses.importcenter.metadata.ImportCatalogService;
import com.ses.importcenter.metadata.ImportMappingView;
import com.ses.importcenter.metadata.ImportSourceRequest;
import com.ses.importcenter.metadata.ImportSourceView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * NF-06 A1a: 取込元（source）とmapping版の登録・更新・一覧API。管理者role＋明示action
 * （{@code import.metadata.manage}）だけが使え、service側で操作ごとに再確認する。
 */
@RestController
@RequestMapping("/api/import-center")
public class ImportCenterSourceApiController {
    private final ImportCatalogService catalog;

    public ImportCenterSourceApiController(ImportCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/sources")
    public ApiResult<ImportCatalogPage<ImportSourceView>> listSources(@RequestParam(defaultValue = "1") long page,
                                                                       @RequestParam(defaultValue = "50") long size) {
        return ApiResult.success(catalog.listSources(page, size));
    }

    @PostMapping("/sources")
    public ApiResult<ImportSourceView> createSource(@RequestBody ImportSourceRequest request) {
        return ApiResult.success(catalog.createSource(request));
    }

    /** 有効（ACTIVE）・停止（DISABLED）の切替。source codeと表示名は変更しない。 */
    @PutMapping("/sources/{id}")
    public ApiResult<ImportSourceView> changeSourceStatus(@PathVariable long id,
                                                          @RequestBody SourceStatusRequest request) {
        if (request == null || request.expectedVersion() == null) {
            throw BusinessException.of(400, "error.import.invalidSource");
        }
        return ApiResult.success(catalog.changeSourceStatus(id, request.expectedVersion(), request.status()));
    }

    @GetMapping("/sources/{id}/mappings")
    public ApiResult<ImportCatalogPage<ImportMappingView>> listMappings(@PathVariable long id,
                                                                         @RequestParam(defaultValue = "1") long page,
                                                                         @RequestParam(defaultValue = "50") long size) {
        return ApiResult.success(catalog.listMappings(id, page, size));
    }

    /** mapping版の登録。既存版は変更せず、内容が異なれば次の版になる。 */
    @PostMapping("/sources/{id}/mappings")
    public ApiResult<ImportMappingView> createMapping(@PathVariable long id, @RequestBody MappingRequest request) {
        if (request == null) throw BusinessException.of(400, "error.import.invalidMapping");
        return ApiResult.success(catalog.createMapping(id, request.entity(), request.schemaVersion(),
                request.headerToCanonicalField()));
    }

    public record SourceStatusRequest(Long expectedVersion, String status) { }

    public record MappingRequest(ImportEntity entity, String schemaVersion, Map<String, String> headerToCanonicalField) { }
}
