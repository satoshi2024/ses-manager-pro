package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.approval.ApprovalActionRequest;
import com.ses.dto.approval.ApprovalRequestCreateRequest;
import com.ses.dto.approval.ApprovalRequestListResponse;
import com.ses.dto.approval.ApprovalRequestView;
import com.ses.dto.approval.ApprovalResubmitRequest;
import com.ses.entity.ApprovalRequest;
import com.ses.service.approval.ApprovalEngineService;
import com.ses.service.approval.ApprovalRequestCommand;
import com.ses.service.approval.ApprovalViewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * 承認engine core向けの汎用API（F1）。対象種別ごとの専用endpoint（見積送信ボタン等）は
 * F2の各対象adapterが自身のcontrollerから{@link ApprovalEngineService}を呼ぶ形で追加する。
 * 本controllerはengineの直接テスト・Demo・route resolution確認用の最小限の入口。
 */
@RestController
@RequestMapping("/api/approval/requests")
@RequiredArgsConstructor
public class ApprovalApiController {

    private final ApprovalEngineService approvalEngineService;
    private final ApprovalViewService approvalViewService;

    @PostMapping
    public ApiResult<ApprovalRequest> create(@Valid @RequestBody ApprovalRequestCreateRequest body) {
        ApprovalRequestCommand command = new ApprovalRequestCommand(
                body.requestType(), body.targetType(), body.targetId(), body.targetVersion(),
                SecurityUtils.currentUserId(), body.organizationId(), body.amountSnapshot(),
                body.payload(), body.diff(), body.idempotencyKey());
        return ApiResult.success(approvalEngineService.request(command));
    }

    @GetMapping
    public ApiResult<ApprovalRequestListResponse> list(
            @RequestParam(defaultValue = "inbox") String view,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            Authentication authentication) {
        return ApiResult.success(approvalViewService.list(view, status, page, pageSize,
                SecurityUtils.currentUserId(), SecurityUtils.currentRole(), authentication));
    }

    @GetMapping("/{id}")
    public ApiResult<ApprovalRequestView> detail(@PathVariable Long id, Authentication authentication) {
        return ApiResult.success(approvalViewService.detail(id, SecurityUtils.currentUserId(),
                SecurityUtils.currentRole(), authentication));
    }

    /** 詳細画面と同じマスク済み差分だけをCSV化する。生のdiff_jsonは直接返さない。 */
    @GetMapping(value = "/{id}/export", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> export(@PathVariable Long id, Authentication authentication) {
        ApprovalRequestView view = approvalViewService.detail(id, SecurityUtils.currentUserId(),
                SecurityUtils.currentRole(), authentication);
        StringBuilder csv = new StringBuilder("field,label,before,after,changed,masked\\r\\n");
        view.diff().forEach(d -> csv.append(csv(d.field())).append(',')
                .append(csv(d.label())).append(',').append(csv(string(d.before())))
                .append(',').append(csv(string(d.after()))).append(',')
                .append(d.changed()).append(',').append(d.masked()).append("\\r\\n"));
        byte[] body = ("\\uFEFF" + csv).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=approval-" + id + "-diff.csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(body);
    }

    private String csv(String value) {
        String safe = value == null ? "" : value.replace("\\r", " ").replace("\\n", " ");
        return "\\\"" + safe.replace("\\\"", "\\\"\\\"") + "\\\"";
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @PostMapping("/{id}/approve")
    public ApiResult<Void> approve(@PathVariable Long id, @RequestBody(required = false) ApprovalActionRequest body) {
        approvalEngineService.approve(id, SecurityUtils.currentUserId(), comment(body));
        return ApiResult.success(null);
    }

    @PostMapping("/{id}/reject")
    public ApiResult<Void> reject(@PathVariable Long id, @RequestBody(required = false) ApprovalActionRequest body) {
        approvalEngineService.reject(id, SecurityUtils.currentUserId(), comment(body));
        return ApiResult.success(null);
    }

    @PostMapping("/{id}/return")
    public ApiResult<Void> returnForRevision(@PathVariable Long id,
                                              @RequestBody(required = false) ApprovalActionRequest body) {
        approvalEngineService.returnForRevision(id, SecurityUtils.currentUserId(), comment(body));
        return ApiResult.success(null);
    }

    @PostMapping("/{id}/resubmit")
    public ApiResult<ApprovalRequest> resubmit(@PathVariable Long id,
                                                @RequestBody(required = false) ApprovalResubmitRequest body) {
        ApprovalResubmitRequest req = body == null ? new ApprovalResubmitRequest(null, null, null) : body;
        return ApiResult.success(approvalEngineService.resubmit(id, SecurityUtils.currentUserId(),
                req.payload(), req.diff(), req.amountSnapshot()));
    }

    @PostMapping("/{id}/withdraw")
    public ApiResult<Void> withdraw(@PathVariable Long id) {
        approvalEngineService.withdraw(id, SecurityUtils.currentUserId());
        return ApiResult.success(null);
    }

    private String comment(ApprovalActionRequest body) {
        return body == null ? null : body.comment();
    }
}
