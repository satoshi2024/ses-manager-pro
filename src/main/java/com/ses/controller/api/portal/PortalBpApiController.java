package com.ses.controller.api.portal;

import com.ses.common.result.ApiResult;
import com.ses.dto.portal.PortalBpAvailabilityDto;
import com.ses.dto.portal.PortalBpAvailabilityRequest;
import com.ses.dto.portal.PortalBpBankAccountRequest;
import com.ses.dto.portal.PortalBpPaymentDto;
import com.ses.dto.portal.PortalBpSubmissionDto;
import com.ses.portal.PortalLoginUser;
import com.ses.service.portal.PortalAuthorizationService;
import com.ses.service.portal.PortalBpService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * BPポータルAPI（/api/portal/bp/**。R3）。
 * 全endpointはPortalAuthorizationServiceが解決した自社bp_company_idをSQL境界に使い、
 * 他BPのID直接指定は404秘匿になる（R4.3・R5）。金額・支払状態の変更APIは存在させない（R3.3）。
 */
@RestController
@RequestMapping("/api/portal/bp")
@RequiredArgsConstructor
public class PortalBpApiController {

    private final PortalBpService bpService;
    private final PortalAuthorizationService authorizationService;
    private final com.ses.service.portal.PortalAuditService auditService;

    private void audit(String action, String targetType, Long targetId, jakarta.servlet.http.HttpServletRequest request) {
        try {
            auditService.record(authorizationService.requireUser(), action, targetType, targetId, request);
        } catch (RuntimeException ignored) {
            // 監査はbest-effort
        }
    }

    private Long bpCompanyId() {
        PortalLoginUser user = authorizationService.requireUser();
        if (!authorizationService.isBpOrg(user)) {
            throw com.ses.common.exception.BusinessException.of(403, "error.forbidden");
        }
        return user.getBpCompanyId();
    }

    // ===== 空き要員（R3.1） =====

    @GetMapping("/availabilities")
    public ApiResult<com.baomidou.mybatisplus.extension.plugins.pagination.Page<PortalBpAvailabilityDto>> availabilities(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size) {
        return ApiResult.success(bpService.availabilities(current, size, bpCompanyId()));
    }

    @PostMapping("/availabilities")
    public ApiResult<PortalBpAvailabilityDto> createAvailability(
            @Valid @RequestBody PortalBpAvailabilityRequest request) {
        return ApiResult.success(bpService.createAvailability(bpCompanyId(), request));
    }

    @PutMapping("/availabilities/{id}")
    public ApiResult<PortalBpAvailabilityDto> updateAvailability(
            @PathVariable Long id, @Valid @RequestBody PortalBpAvailabilityRequest request) {
        return ApiResult.success(bpService.updateAvailability(id, bpCompanyId(), request));
    }

    @PostMapping("/availabilities/{id}/stop")
    public ApiResult<Void> stopAvailability(@PathVariable Long id) {
        bpService.stopAvailability(id, bpCompanyId());
        return ApiResult.success(null);
    }

    // ===== 発注・作業実績（R3.2。受領確認のみ変更可） =====

    @GetMapping("/payments")
    public ApiResult<com.baomidou.mybatisplus.extension.plugins.pagination.Page<PortalBpPaymentDto>> payments(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status) {
        return ApiResult.success(bpService.payments(current, size, bpCompanyId(), status));
    }

    @GetMapping("/payments/{id}")
    public ApiResult<PortalBpPaymentDto> payment(@PathVariable Long id) {
        return ApiResult.success(bpService.payment(id, bpCompanyId()));
    }

    /** 受領確認の一回性CAS（R3.2）。 */
    @PostMapping("/payments/{id}/confirm-receipt")
    public ApiResult<Void> confirmReceipt(@PathVariable Long id, jakarta.servlet.http.HttpServletRequest request) {
        audit("CONFIRM_RECEIPT", "BP_PAYMENT", id, request);
        bpService.confirmReceipt(id, bpCompanyId());
        return ApiResult.success(null);
    }

    // ===== 請求書/作業報告書の提出（R3.2。archive scan通過後のみ公開: R4.4） =====

    @PostMapping("/payments/{id}/submissions")
    public ApiResult<PortalBpSubmissionDto> submitDocument(@PathVariable Long id, jakarta.servlet.http.HttpServletRequest request,
                                                           @RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw com.ses.common.exception.BusinessException.of(400, "error.portal.bp.documentRequired");
        }
        try {
            audit("SUBMIT", "BP_PAYMENT", id, request);
            return ApiResult.success(bpService.submitDocument(id, bpCompanyId(),
                    file.getOriginalFilename(), file.getContentType(), file.getBytes()));
        } catch (IOException e) {
            throw com.ses.common.exception.BusinessException.of(400, "error.portal.bp.documentReadFailed");
        }
    }

    @GetMapping("/payments/{id}/submissions")
    public ApiResult<List<PortalBpSubmissionDto>> submissions(@PathVariable Long id) {
        return ApiResult.success(bpService.submissions(id, bpCompanyId()));
    }

    @GetMapping("/payments/{paymentId}/submissions/{documentId}/download")
    public ResponseEntity<InputStreamResource> downloadSubmission(@PathVariable Long paymentId,
                                                                  @PathVariable Long documentId,
                                                                  jakarta.servlet.http.HttpServletRequest request) {
        audit("DOWNLOAD_SUBMISSION", "BP_PAYMENT", paymentId, request);
        InputStream stream = bpService.downloadSubmission(documentId, paymentId, bpCompanyId());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + URLEncoder.encode("提出物_" + documentId + ".pdf",
                                StandardCharsets.UTF_8).replace("+", "%20"))
                .body(new InputStreamResource(stream));
    }

    // ===== 支払状況（R3.3。参照のみ） =====

    // ===== 口座変更申請（R3.4） =====

    @GetMapping("/bank-accounts")
    public ApiResult<List<com.ses.dto.bpcompany.BpBankAccountDto>> bankAccounts() {
        return ApiResult.success(bpService.bankAccounts(bpCompanyId()));
    }

    @PostMapping("/bank-accounts")
    public ApiResult<Void> requestBankAccountChange(@Valid @RequestBody PortalBpBankAccountRequest request,
                                                     jakarta.servlet.http.HttpServletRequest httpRequest) {
        audit("BANK_REQUEST", "BP_BANK_ACCOUNT", null, httpRequest);
        bpService.requestBankAccountChange(bpCompanyId(), request);
        return ApiResult.success(null);
    }
}
