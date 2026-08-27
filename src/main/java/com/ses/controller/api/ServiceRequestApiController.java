package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.servicedesk.ServiceCommentCreateRequest;
import com.ses.dto.servicedesk.ServiceCommentDto;
import com.ses.dto.servicedesk.ServiceRequestCreateRequest;
import com.ses.dto.servicedesk.ServiceRequestDto;
import com.ses.dto.servicedesk.ServiceRequestStatusChangeRequest;
import com.ses.dto.servicedesk.ServiceRequestUpdateRequest;
import com.ses.entity.ServiceRequest;
import com.ses.entity.ServiceSlaPolicy;
import com.ses.mapper.ServiceSlaPolicyMapper;
import com.ses.service.servicedesk.ServiceRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 内部サービスデスク管理API (/api/service-desk/requests)
 */
@RestController
@RequestMapping("/api/service-desk/requests")
@RequiredArgsConstructor
public class ServiceRequestApiController {

    private final ServiceRequestService serviceRequestService;
    private final ServiceSlaPolicyMapper slaPolicyMapper;
    private final com.ses.service.servicedesk.ServiceRequestExportService exportService;

    /**
     * 問い合わせ一覧検索（ページネーション・DataScope適用）
     */
    @GetMapping
    public ApiResult<Page<ServiceRequestDto>> list(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Long customerId) {
        Page<ServiceRequestDto> result = serviceRequestService.searchInternalRequests(current, size, keyword, status, priority, category, customerId);
        return ApiResult.success(result);
    }

    /**
     * 問い合わせ詳細取得（内部メモ含む）
     */
    @GetMapping("/{id}")
    public ApiResult<ServiceRequestDto> get(@PathVariable Long id) {
        ServiceRequestDto dto = serviceRequestService.getInternalDetail(id);
        return ApiResult.success(dto);
    }

    /**
     * 新規問い合わせ起票
     */
    @PostMapping
    public ApiResult<ServiceRequest> create(@Valid @RequestBody ServiceRequestCreateRequest req) {
        Long userId = SecurityUtils.currentUserId();
        ServiceRequest created = serviceRequestService.createRequest(req, userId, false, null);
        return ApiResult.success(created);
    }

    /**
     * 問い合わせ属性更新
     */
    @PutMapping("/{id}")
    public ApiResult<Void> update(@PathVariable Long id, @RequestBody ServiceRequestUpdateRequest req) {
        serviceRequestService.updateRequest(id, req);
        return ApiResult.success(null);
    }

    /**
     * ステータス変更
     */
    @PostMapping("/{id}/status")
    public ApiResult<Void> changeStatus(@PathVariable Long id, @Valid @RequestBody ServiceRequestStatusChangeRequest req) {
        Long userId = SecurityUtils.currentUserId();
        String role = SecurityUtils.currentRole();
        String username = SecurityUtils.currentUsername();
        serviceRequestService.changeStatus(id, req, userId, "INTERNAL_USER", username != null ? username : "内部管理者");
        return ApiResult.success(null);
    }

    /**
     * コメント・内部メモ投稿
     */
    @PostMapping("/{id}/comments")
    public ApiResult<ServiceCommentDto> addComment(@PathVariable Long id, @Valid @RequestBody ServiceCommentCreateRequest req) {
        Long userId = SecurityUtils.currentUserId();
        String username = SecurityUtils.currentUsername();
        ServiceCommentDto commentDto = serviceRequestService.addComment(
                id, req, userId, "INTERNAL_USER", username != null ? username : "内部ユーザー", false
        );
        return ApiResult.success(commentDto);
    }

    /**
     * SLAポリシー一覧取得
     */
    @GetMapping("/policies")
    public ApiResult<List<ServiceSlaPolicy>> policies() {
        List<ServiceSlaPolicy> list = slaPolicyMapper.selectList(
                new LambdaQueryWrapper<ServiceSlaPolicy>().eq(ServiceSlaPolicy::getStatus, "ACTIVE")
        );
        return ApiResult.success(list);
    }

    /**
     * サービスデスク一覧 CSV エクスポート
     */
    @GetMapping("/export")
    public void exportCsv(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Long customerId,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        response.setContentType("text/csv; charset=UTF-8");
        String filename = "service_requests_" + java.time.LocalDate.now() + ".csv";
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        exportService.exportRequestsToCsv(response.getOutputStream(), keyword, status, priority, category, customerId);
    }
}
