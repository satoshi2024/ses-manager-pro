package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.service.changerequest.EngineerChangeRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 変更申請管理API（HR/管理者=全件、マネージャー=組織scope配下。design §6.2）。
 * menu付与に加えて@PreAuthorizeで二重に境界を張る。
 */
@RestController
@RequestMapping("/api/engineer-change-requests")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('管理者','HR','マネージャー')")
public class EngineerChangeRequestApiController {

    private final EngineerChangeRequestService changeRequestService;

    @GetMapping
    public ApiResult<Page<EngineerChangeRequestService.ChangeRequestDto>> list(
            @RequestParam(required = false) String engineerName,
            @RequestParam(required = false) String requestType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size) {
        return ApiResult.success(changeRequestService.pageManagement(
                engineerName, requestType, status, current, size));
    }

    @GetMapping("/{id}")
    public ApiResult<EngineerChangeRequestService.ChangeRequestDto> detail(@PathVariable Long id) {
        return ApiResult.success(changeRequestService.detailManagement(id));
    }

    /** 添付ダウンロード（requestId境界。scopeはservice層で検証、営業は@PreAuthorizeで403）。 */
    @GetMapping("/{id}/attachment")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.InputStreamResource> downloadAttachment(@PathVariable Long id) {
        EngineerChangeRequestService.AttachmentDownload download =
                changeRequestService.downloadAttachmentManagement(id);
        String encodedName = java.net.URLEncoder.encode(download.originalName(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        return org.springframework.http.ResponseEntity.ok()
                .contentType(parseMediaType(download.contentType()))
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodedName)
                .body(new org.springframework.core.io.InputStreamResource(download.stream()));
    }

    private org.springframework.http.MediaType parseMediaType(String contentType) {
        try {
            return org.springframework.http.MediaType.parseMediaType(contentType);
        } catch (RuntimeException e) {
            return org.springframework.http.MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
