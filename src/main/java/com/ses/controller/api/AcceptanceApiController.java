package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.acceptance.AcceptanceActionRequest;
import com.ses.dto.acceptance.AcceptanceSubmitRequest;
import com.ses.entity.Acceptance;
import com.ses.service.AcceptanceService;
import com.ses.service.approval.ApprovalTargetAdapterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 月次検収API（order-acceptance-workflow / B1）。
 * グリッド一覧・提出・検収・差戻し・再提出・検収取消の承認申請。
 */
@RestController
@RequestMapping("/api/acceptances")
@RequiredArgsConstructor
public class AcceptanceApiController {

    private final AcceptanceService acceptanceService;
    private final ApprovalTargetAdapterRegistry approvalRegistry;

    @GetMapping
    public ApiResult<?> list(@RequestParam(defaultValue = "1") long current,
                             @RequestParam(defaultValue = "50") long size,
                             @RequestParam String workMonth,
                             @RequestParam(required = false) String status,
                             @RequestParam(required = false) Long customerId,
                             @RequestParam(required = false) Long engineerId) {
        return ApiResult.success(acceptanceService.pageGrid(current, size, workMonth, status, customerId, engineerId));
    }

    /** 提出（未提出→提出済）。work record確定済みであること。 */
    @PostMapping("/submit")
    public ApiResult<Acceptance> submit(@jakarta.validation.Valid @RequestBody AcceptanceSubmitRequest request) {
        return ApiResult.success(acceptanceService.submit(request.getContractId(), request.getWorkMonth()));
    }

    /** 検収（提出済→検収済）。 */
    @PostMapping("/{id}/accept")
    public ApiResult<Acceptance> accept(@PathVariable Long id,
                                        @RequestBody(required = false) AcceptanceActionRequest request) {
        Long contactId = request == null ? null : request.getCustomerContactId();
        return ApiResult.success(acceptanceService.accept(id, contactId));
    }

    /** 差戻し（提出済→差戻し）。理由必須。 */
    @PostMapping("/{id}/reject")
    public ApiResult<Acceptance> reject(@PathVariable Long id, @RequestBody AcceptanceActionRequest request) {
        String comment = request == null ? null : request.getComment();
        return ApiResult.success(acceptanceService.reject(id, comment));
    }

    /** 再提出（差戻し→提出済）。 */
    @PostMapping("/{id}/resubmit")
    public ApiResult<Acceptance> resubmit(@PathVariable Long id) {
        return ApiResult.success(acceptanceService.resubmit(id));
    }

    /** 検収書原本（ACCEPTANCE）を文書台帳へ登録する（R3.1）。 */
    @PostMapping("/{id}/document")
    public ApiResult<Acceptance> uploadDocument(@PathVariable Long id,
                                                @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        return ApiResult.success(acceptanceService.uploadDocument(id, file));
    }

    /** 検収書原本をdownloadする（検収一覧と同じscope）。 */
    @GetMapping("/{id}/document")
    public ResponseEntity<org.springframework.core.io.InputStreamResource> downloadDocument(@PathVariable Long id) {
        java.io.InputStream stream = acceptanceService.downloadDocument(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"acceptance_" + id + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new org.springframework.core.io.InputStreamResource(stream));
    }

    /** 検収取消の承認申請（R3.4: 検収済work recordの再openには検収取消承認が必要）。 */
    @PostMapping("/{id}/cancel-approval")
    public ApiResult<?> requestCancelApproval(@PathVariable Long id,
                                              @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> command = new LinkedHashMap<>();
        if (body != null) {
            command.put("reason", body.getOrDefault("reason", ""));
        }
        return ApiResult.success(approvalRegistry.request("acceptance.cancel", "ACCEPTANCE", id, command));
    }
}
