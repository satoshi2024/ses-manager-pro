package com.ses.controller.api.portal;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.dto.portal.PortalCsatCreateRequest;
import com.ses.dto.portal.PortalServiceCommentDto;
import com.ses.dto.portal.PortalServiceRequestDto;
import com.ses.dto.servicedesk.ServiceCommentCreateRequest;
import com.ses.dto.servicedesk.ServiceCommentDto;
import com.ses.dto.servicedesk.ServiceRequestCreateRequest;
import com.ses.entity.ServiceRequest;
import com.ses.portal.PortalLoginUser;
import com.ses.service.portal.PortalAuthorizationService;
import com.ses.service.servicedesk.ServiceRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 顧客ポータル向けサービスデスクAPI (/api/portal/customer/service-desk/requests)
 * 内部メモ・原価・内部担当者ID等の機密情報は構造的に除外されたDTOで返却する。
 * 他組織IDへのアクセスは404秘匿する。
 */
@RestController
@RequestMapping("/api/portal/customer/service-desk/requests")
@RequiredArgsConstructor
public class PortalCustomerServiceDeskApiController {

    private final ServiceRequestService serviceRequestService;
    private final PortalAuthorizationService authorizationService;

    private Long customerId() {
        PortalLoginUser user = authorizationService.requireUser();
        if (!authorizationService.isCustomerOrg(user)) {
            throw BusinessException.of(403, "error.forbidden");
        }
        return user.getCustomerId();
    }

    private Long portalUserId() {
        return authorizationService.requireUser().getPortalUserId();
    }

    private String portalUserName() {
        return authorizationService.requireUser().getUsername();
    }

    /**
     * 自社の問い合わせ一覧検索
     */
    @GetMapping
    public ApiResult<Page<PortalServiceRequestDto>> list(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        Page<PortalServiceRequestDto> result = serviceRequestService.searchPortalRequests(
                current, size, keyword, status, customerId()
        );
        return ApiResult.success(result);
    }

    /**
     * 自社の問い合わせ詳細取得（内部メモ除外）
     */
    @GetMapping("/{id}")
    public ApiResult<PortalServiceRequestDto> get(@PathVariable Long id) {
        PortalServiceRequestDto dto = serviceRequestService.getPortalDetail(id, customerId());
        return ApiResult.success(dto);
    }

    /**
     * ポータルからの新規問い合わせ起票
     */
    @PostMapping
    public ApiResult<PortalServiceRequestDto> create(@Valid @RequestBody ServiceRequestCreateRequest req) {
        Long custId = customerId();
        Long userId = portalUserId();

        // 顧客IDはポータルユーザーの所属組織に強制固定
        req.setCustomerId(custId);
        req.setChannel("PORTAL");

        ServiceRequest created = serviceRequestService.createRequest(req, null, true, userId);
        PortalServiceRequestDto dto = serviceRequestService.getPortalDetail(created.getId(), custId);
        return ApiResult.success(dto);
    }

    /**
     * ポータルからの返信コメント投稿（自動的にPORTAL_VISIBLE、WAITING_CUSTOMER時は自動再開）
     */
    @PostMapping("/{id}/comments")
    public ApiResult<ServiceCommentDto> addComment(@PathVariable Long id, @Valid @RequestBody ServiceCommentCreateRequest req) {
        // 自社スコープ検証
        serviceRequestService.getPortalDetail(id, customerId());

        ServiceCommentDto commentDto = serviceRequestService.addComment(
                id, req, portalUserId(), "PORTAL_USER", portalUserName(), true
        );
        return ApiResult.success(commentDto);
    }

    /**
     * 解決・クローズ後のCSAT評価回答（1回限り、二重回答は409拒否）
     */
    @PostMapping("/{id}/csat")
    public ApiResult<Void> submitCsat(@PathVariable Long id, @Valid @RequestBody PortalCsatCreateRequest req) {
        serviceRequestService.submitCsat(id, req, customerId(), portalUserId());
        return ApiResult.success(null);
    }
}
