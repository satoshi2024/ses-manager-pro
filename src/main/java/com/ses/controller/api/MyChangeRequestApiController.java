package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.changerequest.EngineerChangeRequestService;
import lombok.RequiredArgsConstructor;
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
