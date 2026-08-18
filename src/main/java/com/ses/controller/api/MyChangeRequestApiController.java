package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.changerequest.EngineerChangeRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 要員ポータル（変更申請）API。本人scopeはengineer-account linkから解決（design §3）。
 * request_typeごとのDTO allowlist検証はService層で行い、allowlist外のkeyは拒否する（design §6.3）。
 */
@RestController
@RequestMapping("/api/my/change-requests")
@RequiredArgsConstructor
public class MyChangeRequestApiController {

    private final EngineerAccountLinkService linkService;
    private final EngineerChangeRequestService changeRequestService;
    private final com.ses.service.DocumentService documentService;
    private final com.ses.mapper.DocumentLinkMapper documentLinkMapper;
    private final java.time.Clock clock;

    private Long currentEngineerId() {
        Long engineerId = linkService.findEngineerIdByUserId(SecurityUtils.currentUserId());
        if (engineerId == null) {
            throw BusinessException.of(403, "error.my.notLinked");
        }
        return engineerId;
    }

    @GetMapping
    public ApiResult<Page<EngineerChangeRequestService.ChangeRequestDto>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size) {
        return ApiResult.success(changeRequestService.pageOwn(currentEngineerId(), status, current, size));
    }

    @GetMapping("/{id}")
    public ApiResult<EngineerChangeRequestService.ChangeRequestDto> detail(@PathVariable Long id) {
        return ApiResult.success(changeRequestService.detailOwn(currentEngineerId(), id));
    }

    @PostMapping(value = "/attachment", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<Map<String, Object>> uploadAttachment(
            @org.springframework.web.bind.annotation.RequestPart("file") org.springframework.web.multipart.MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.of(400, "error.file.empty");
        }
        Long engineerId = currentEngineerId();
        try {
            com.ses.dto.document.DocumentRegisterRequest req = com.ses.dto.document.DocumentRegisterRequest.builder()
                    .documentType("CHANGE_REQUEST_ATTACHMENT")
                    .title(file.getOriginalFilename() == null ? "attachment" : file.getOriginalFilename())
                    .originalName(file.getOriginalFilename() == null ? "attachment" : file.getOriginalFilename())
                    .contentType(file.getContentType() == null ? "application/octet-stream" : file.getContentType())
                    .sourceType("RECEIVED")
                    .direction("INCOMING")
                    .counterpartyType("INTERNAL")
                    .transactionDate(java.time.LocalDate.now(clock))
                    .businessKey("CR_ATTACH:" + engineerId + ":" + System.nanoTime())
                    .versionDiscriminator("v1")
                    .targetType("ENGINEER")
                    .targetId(engineerId)
                    .createdBy(SecurityUtils.currentUserId())
                    .build();
            com.ses.entity.Document document = documentService.registerReceived(req, file.getInputStream());
            return ApiResult.success(Map.of("documentId", document.getId(), "originalName", document.getTitle()));
        } catch (java.io.IOException e) {
            throw BusinessException.of(500, "error.file.readFailed");
        }
    }

    @PostMapping
    public ApiResult<EngineerChangeRequestService.ChangeRequestDto> create(@RequestBody CreateRequest request) {
        String requestType = request == null ? null : request.getRequestType();
        Map<String, Object> payload = request == null ? null : request.getPayload();
        String reason = request == null ? null : request.getReason();
        Long attachmentDocumentId = request == null ? null : request.getAttachmentDocumentId();
        return ApiResult.success(changeRequestService.createDraft(currentEngineerId(), requestType, payload,
                reason, attachmentDocumentId));
    }

    @PostMapping("/{id}/submit")
    public ApiResult<EngineerChangeRequestService.ChangeRequestDto> submit(@PathVariable Long id) {
        return ApiResult.success(changeRequestService.submit(currentEngineerId(), id));
    }

    @PostMapping("/{id}/withdraw")
    public ApiResult<EngineerChangeRequestService.ChangeRequestDto> withdraw(@PathVariable Long id) {
        return ApiResult.success(changeRequestService.withdraw(currentEngineerId(), id));
    }

    @PostMapping("/{id}/resubmit")
    public ApiResult<EngineerChangeRequestService.ChangeRequestDto> resubmit(@PathVariable Long id) {
        return ApiResult.success(changeRequestService.resubmit(currentEngineerId(), id));
    }

    @GetMapping("/{id}/attachment")
    public ResponseEntity<org.springframework.core.io.InputStreamResource> downloadAttachment(@PathVariable Long id) {
        EngineerChangeRequestService.AttachmentDownload download =
                changeRequestService.downloadAttachment(currentEngineerId(), id);
        String encodedName = java.net.URLEncoder.encode(download.originalName(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
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

    public static class CreateRequest {
        private String requestType;
        private Map<String, Object> payload;
        private String reason;
        private Long attachmentDocumentId;

        public String getRequestType() {
            return requestType;
        }

        public void setRequestType(String requestType) {
            this.requestType = requestType;
        }

        public Map<String, Object> getPayload() {
            return payload;
        }

        public void setPayload(Map<String, Object> payload) {
            this.payload = payload;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public Long getAttachmentDocumentId() {
            return attachmentDocumentId;
        }

        public void setAttachmentDocumentId(Long attachmentDocumentId) {
            this.attachmentDocumentId = attachmentDocumentId;
        }
    }
}
