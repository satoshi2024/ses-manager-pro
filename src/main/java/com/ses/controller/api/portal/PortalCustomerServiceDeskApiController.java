package com.ses.controller.api.portal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.dto.portal.PortalCsatCreateRequest;
import com.ses.dto.portal.PortalServiceCommentCreateRequest;
import com.ses.dto.portal.PortalServiceCommentDto;
import com.ses.dto.portal.PortalServiceRequestCreateRequest;
import com.ses.dto.portal.PortalServiceRequestDto;
import com.ses.dto.servicedesk.ServiceCommentCreateRequest;
import com.ses.dto.servicedesk.ServiceCommentDto;
import com.ses.dto.servicedesk.ServiceRequestCreateRequest;
import com.ses.entity.Contract;
import com.ses.entity.CustomerContact;
import com.ses.entity.DocumentVersion;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.entity.ServiceAttachmentLink;
import com.ses.entity.ServiceRequest;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerContactMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.ServiceAttachmentLinkMapper;
import com.ses.portal.PortalLoginUser;
import com.ses.service.FileStorageService;
import com.ses.service.portal.PortalAuthorizationService;
import com.ses.service.servicedesk.ServiceRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
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
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 顧客ポータル向けサービスデスクAPI (/api/portal/customer/service-desk/requests)
 * 内部メモ・原価・内部担当者ID等の機密情報は構造的に除外されたDTOで返却する
 * 他組織IDへのアクセスは404秘匿する
 * ポータル権限 (service-desk.view / service-desk.create) を強制検証する
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
    private final ContractMapper contractMapper;
    private final ProjectMapper projectMapper;
    private final CustomerContactMapper contactMapper;
    private final EngineerMapper engineerMapper;

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
        PortalLoginUser user = authorizationService.requireUser();
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        return user.getEmail() != null ? user.getEmail() : "ポータル利用者";
    }

    /**
     * ポータル向け問い合わせ一覧検索（自社スコープ限定 & 権限検証）
     */
    @GetMapping
    public ApiResult<Page<PortalServiceRequestDto>> list(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        authorizationService.assertPermission(authorizationService.requireUser(), "service-desk.view");
        Page<PortalServiceRequestDto> result = serviceRequestService.searchPortalRequests(current, size, keyword, status, customerId());
        return ApiResult.success(result);
    }

    /**
     * ポータル向け問い合わせ詳細取得（自社スコープ限定 & 内部メモ非公開 & 権限検証）
     */
    @GetMapping("/{id}")
    public ApiResult<PortalServiceRequestDto> get(@PathVariable Long id) {
        authorizationService.assertPermission(authorizationService.requireUser(), "service-desk.view");
        PortalServiceRequestDto dto = serviceRequestService.getPortalDetail(id, customerId());
        return ApiResult.success(dto);
    }

    /**
     * ポータルからの新規問い合わせ起票
     */
    @PostMapping
    public ApiResult<PortalServiceRequestDto> create(@Valid @RequestBody PortalServiceRequestCreateRequest req) {
        authorizationService.assertPermission(authorizationService.requireUser(), "service-desk.create");
        Long custId = customerId();
        Long userId = portalUserId();

        // 契約が指定された場合、自社契約であることを検証
        if (req.getContractId() != null) {
            Contract contract = contractMapper.selectById(req.getContractId());
            if (contract == null || !Objects.equals(contract.getCustomerId(), custId)) {
                throw BusinessException.of(400, "指定された契約は自社に紐付いていません");
            }
        }

        // 案件が指定された場合、自社案件であることを検証
        if (req.getProjectId() != null) {
            Project project = projectMapper.selectById(req.getProjectId());
            if (project == null || !Objects.equals(project.getCustomerId(), custId)) {
                throw BusinessException.of(400, "指定された案件は自社に紐付いていません");
            }
        }

        // 顧客担当者が指定された場合、自社担当者であることを検証
        if (req.getContactId() != null) {
            CustomerContact contact = contactMapper.selectById(req.getContactId());
            if (contact == null || !Objects.equals(contact.getCustomerId(), custId)) {
                throw BusinessException.of(400, "指定された顧客担当者は自社に紐付いていません");
            }
        }

        // 要員が指定された場合の存在検証および自社契約所属検証
        if (req.getEngineerId() != null) {
            Engineer engineer = engineerMapper.selectById(req.getEngineerId());
            if (engineer == null) {
                throw BusinessException.of(400, "指定された要員が見つかりません");
            }
            Long contractCount = contractMapper.selectCount(
                    new LambdaQueryWrapper<Contract>()
                            .eq(Contract::getCustomerId, custId)
                            .eq(Contract::getEngineerId, req.getEngineerId())
            );
            if (contractCount == null || contractCount == 0) {
                throw BusinessException.of(400, "指定された要員は自社の契約に紐付いていません");
            }
        }

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
     * ポータルからの返信コメント投稿
     */
    @PostMapping("/{id}/comments")
    public ApiResult<PortalServiceCommentDto> addComment(@PathVariable Long id, @Valid @RequestBody PortalServiceCommentCreateRequest req) {
        authorizationService.assertPermission(authorizationService.requireUser(), "service-desk.create");

        // 自社スコープ検証 (404 秘匿)
        serviceRequestService.getPortalDetail(id, customerId());

        ServiceCommentCreateRequest internalReq = ServiceCommentCreateRequest.builder()
                .commentText(req.getCommentText())
                .visibility("PORTAL_VISIBLE")
                .build();

        ServiceCommentDto commentDto = serviceRequestService.addComment(
                id, internalReq, portalUserId(), "PORTAL_USER", portalUserName(), true
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
     * 解決・クローズ後のCSAT評価回答
     */
    @PostMapping("/{id}/csat")
    public ApiResult<Void> submitCsat(@PathVariable Long id, @Valid @RequestBody PortalCsatCreateRequest req) {
        authorizationService.assertPermission(authorizationService.requireUser(), "service-desk.create");
        serviceRequestService.submitCsat(id, req, customerId(), portalUserId());
        return ApiResult.success(null);
    }

    /**
     * ポータル添付ファイルダウンロード
     */
    @GetMapping("/{id}/attachments/{attachmentId}/download")
    public ResponseEntity<Resource> downloadAttachment(@PathVariable Long id, @PathVariable Long attachmentId) {
        authorizationService.assertPermission(authorizationService.requireUser(), "service-desk.view");

        // 1. リクエスト自社スコープ検証
        serviceRequestService.getPortalDetail(id, customerId());

        // 2. 添付ファイルリンク検証
        ServiceAttachmentLink link = attachmentLinkMapper.selectById(attachmentId);
        if (link == null || !Objects.equals(link.getServiceRequestId(), id) || !"PORTAL_VISIBLE".equals(link.getVisibility())) {
            throw BusinessException.of(404, "error.notFound");
        }

        // 3. DocumentVersion 取得
        DocumentVersion version = documentVersionMapper.selectOne(
                new LambdaQueryWrapper<DocumentVersion>()
                        .eq(DocumentVersion::getDocumentId, link.getDocumentId())
                        .orderByDesc(DocumentVersion::getVersionNo)
                        .last("LIMIT 1"));
        if (version == null || version.getStorageKey() == null) {
            throw BusinessException.of(404, "error.notFound");
        }

        // 4. ストレージから読み込み
        Resource resource = fileStorageService.load(version.getStorageKey());
        if (resource == null || !resource.exists()) {
            throw BusinessException.of(404, "error.notFound");
        }

        // 5. Content-Type 判定
        String fileName = link.getFileName() != null ? link.getFileName() : "attachment";
        String contentType = URLConnection.guessContentTypeFromName(fileName);
        if (contentType == null) {
            contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }

        String contentDisposition = ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build()
                .toString();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .body(resource);
    }
}
