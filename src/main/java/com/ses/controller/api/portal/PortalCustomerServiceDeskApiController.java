package com.ses.controller.api.portal;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.dto.portal.PortalCsatCreateRequest;
import com.ses.dto.portal.PortalServiceCommentDto;
import com.ses.dto.portal.PortalServiceRequestCreateRequest;
import com.ses.dto.portal.PortalServiceRequestDto;
import com.ses.dto.servicedesk.ServiceCommentCreateRequest;
import com.ses.dto.servicedesk.ServiceCommentDto;
import com.ses.dto.servicedesk.ServiceRequestCreateRequest;
import com.ses.entity.DocumentVersion;
import com.ses.entity.ServiceAttachmentLink;
import com.ses.entity.ServiceRequest;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.mapper.ServiceAttachmentLinkMapper;
import com.ses.portal.PortalLoginUser;
import com.ses.service.FileStorageService;
import com.ses.service.portal.PortalAuthorizationService;
import com.ses.service.servicedesk.ServiceRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
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

import java.net.URLConnection;
import java.util.Objects;

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
    private final ServiceAttachmentLinkMapper attachmentLinkMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final FileStorageService fileStorageService;

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
     * ポータルからの新規問い合わせ起票 (WIP-8: ポータル専用DTO利用)
     */
    @PostMapping
    public ApiResult<PortalServiceRequestDto> create(@Valid @RequestBody PortalServiceRequestCreateRequest req) {
        Long custId = customerId();
        Long userId = portalUserId();

        ServiceRequestCreateRequest internalReq = ServiceRequestCreateRequest.builder()
                .customerId(custId)
                .contactId(req.getContactId())
                .contractId(req.getContractId())
                .projectId(req.getProjectId())
                .engineerId(req.getEngineerId())
                .category(req.getCategory())
                .priority(req.getPriority())
                .channel("PORTAL")
                .subject(req.getSubject())
                .description(req.getDescription())
                .ownerUserId(null)
                .build();

        ServiceRequest created = serviceRequestService.createRequest(internalReq, null, true, userId);
        PortalServiceRequestDto dto = serviceRequestService.getPortalDetail(created.getId(), custId);
        return ApiResult.success(dto);
    }

    /**
     * ポータルからの返信コメント投稿（自動的にPORTAL_VISIBLE、WAITING_CUSTOMER時は自動再開、WIP-8: 内部項目除外DTO返却）
     */
    @PostMapping("/{id}/comments")
    public ApiResult<PortalServiceCommentDto> addComment(@PathVariable Long id, @Valid @RequestBody ServiceCommentCreateRequest req) {
        // 自社スコープ検証 (404 秘匿)
        serviceRequestService.getPortalDetail(id, customerId());

        ServiceCommentDto commentDto = serviceRequestService.addComment(
                id, req, portalUserId(), "PORTAL_USER", portalUserName(), true
        );

        PortalServiceCommentDto portalDto = PortalServiceCommentDto.builder()
                .id(commentDto.getId())
                .serviceRequestId(commentDto.getServiceRequestId())
                .authorType(commentDto.getAuthorType())
                .authorName(commentDto.getAuthorName())
                .commentText(commentDto.getCommentText())
                .createdAt(commentDto.getCreatedAt())
                .build();

        return ApiResult.success(portalDto);
    }

    /**
     * 解決・クローズ後のCSAT評価回答（1回限り、二重回答は409拒否）
     */
    @PostMapping("/{id}/csat")
    public ApiResult<Void> submitCsat(@PathVariable Long id, @Valid @RequestBody PortalCsatCreateRequest req) {
        serviceRequestService.submitCsat(id, req, customerId(), portalUserId());
        return ApiResult.success(null);
    }

    /**
     * ポータル添付ファイルダウンロード (WIP-5: 自社スコープおよびPORTAL_VISIBLE検証)
     */
    @GetMapping("/{id}/attachments/{attachmentId}/download")
    public ResponseEntity<Resource> downloadAttachment(@PathVariable Long id, @PathVariable Long attachmentId) {
        // 1. リクエスト自社スコープ検証
        serviceRequestService.getPortalDetail(id, customerId());

        // 2. 添付ファイルリンク検証
        ServiceAttachmentLink link = attachmentLinkMapper.selectById(attachmentId);
        if (link == null || !Objects.equals(link.getServiceRequestId(), id) || !"PORTAL_VISIBLE".equals(link.getVisibility())) {
            throw BusinessException.of(404, "error.notFound");
        }

        // 3. DocumentVersion 取得
        DocumentVersion version = documentVersionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DocumentVersion>()
                        .eq(DocumentVersion::getDocumentId, link.getDocumentId())
                        .orderByDesc(DocumentVersion::getVersionNo)
                        .last("LIMIT 1"));
        if (version == null || version.getStorageKey() == null) {
            throw BusinessException.of(404, "error.notFound");
        }

        // 4. ストレージから読み込み
        Resource resource = fileStorageService.load(version.getStorageKey());
        String contentType = URLConnection.guessContentTypeFromName(link.getFileName());
        MediaType mediaType = contentType != null ? MediaType.parseMediaType(contentType) : MediaType.APPLICATION_OCTET_STREAM;

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + link.getFileName() + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(resource);
    }
}
