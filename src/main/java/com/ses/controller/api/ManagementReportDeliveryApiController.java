package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.report.ReportDeliveryResult;
import com.ses.dto.report.ReportDownload;
import com.ses.service.report.ReportDeliveryService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理レポートdelivery API。token/scope/再認証の判定はserviceへ集約する。 */
@RestController
@RequestMapping("/api/management-reports")
@RequiredArgsConstructor
public class ManagementReportDeliveryApiController {

    private final ReportDeliveryService deliveryService;

    @PostMapping("/runs/{runId}/deliver")
    public ApiResult<ReportDeliveryResult> deliver(@PathVariable Long runId,
                                                   @RequestParam(required = false) String previewHash) {
        return ApiResult.success(deliveryService.deliver(runId, previewHash));
    }

    @PostMapping("/deliveries/{deliveryId}/reauthenticate")
    public ApiResult<Boolean> reauthenticate(@PathVariable Long deliveryId,
                                             @RequestBody ReauthenticateRequest request) {
        deliveryService.reauthenticate(deliveryId, request == null ? null : request.password());
        return ApiResult.success(true);
    }

    @GetMapping("/deliveries/{deliveryId}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable Long deliveryId,
                                                        @RequestParam String token,
                                                        @RequestParam(defaultValue = "PDF") String format) {
        ReportDownload download = deliveryService.download(deliveryId, token, format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.getFileName() + "\"")
                .contentType(MediaType.parseMediaType(download.getContentType()))
                .body(new InputStreamResource(download.getStream()));
    }

    @GetMapping("/deliveries/{deliveryId}/preview")
    public ResponseEntity<InputStreamResource> preview(@PathVariable Long deliveryId,
                                                       @RequestParam String token,
                                                       @RequestParam(defaultValue = "PDF") String format) {
        ReportDownload download = deliveryService.preview(deliveryId, token, format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + download.getFileName() + "\"")
                .contentType(MediaType.parseMediaType(download.getContentType()))
                .body(new InputStreamResource(download.getStream()));
    }

    @PostMapping("/deliveries/{deliveryId}/retry")
    public ApiResult<Boolean> retry(@PathVariable Long deliveryId) {
        deliveryService.retry(deliveryId);
        return ApiResult.success(true);
    }

    @PostMapping("/deliveries/{deliveryId}/manual-replay")
    public ApiResult<Boolean> manualReplay(@PathVariable Long deliveryId) {
        deliveryService.manualReplay(deliveryId);
        return ApiResult.success(true);
    }

    public record ReauthenticateRequest(String password) {
    }
}
