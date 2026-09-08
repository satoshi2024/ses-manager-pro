package com.ses.service.security.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.BpAvailabilityIngestion;
import com.ses.entity.DocumentLink;
import com.ses.entity.DocumentVersion;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerCertification;
import com.ses.entity.ProjectIngestion;
import com.ses.entity.Proposal;
import com.ses.entity.ResumeIngestion;
import com.ses.entity.ServiceAttachmentLink;
import com.ses.entity.ServiceRequest;
import com.ses.mapper.BpAvailabilityIngestionMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.mapper.EngineerCertificationMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectIngestionMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.mapper.ResumeIngestionMapper;
import com.ses.service.MenuCacheService;
import com.ses.service.security.DataScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * ファイルダウンロード時のアクセス制御（A8-04）を行うサービス。
 */
@Service
@RequiredArgsConstructor
public class FileScopeValidationService {

    private final ResumeIngestionMapper resumeIngestionMapper;
    private final EngineerMapper engineerMapper;
    private final ProposalMapper proposalMapper;
    private final ProjectIngestionMapper projectIngestionMapper;
    private final BpAvailabilityIngestionMapper bpAvailabilityIngestionMapper;
    private final ObjectProvider<DocumentVersionMapper> documentVersionMapperProvider;
    private final ObjectProvider<DocumentLinkMapper> documentLinkMapperProvider;
    private final ObjectProvider<com.ses.mapper.DocumentMapper> documentMapperProvider;
    private final ObjectProvider<com.ses.service.EngineerAccountLinkService> engineerAccountLinkServiceProvider;
    private final ObjectProvider<com.ses.service.security.OrganizationScopeService> organizationScopeServiceProvider;
    private final DataScopeService dataScopeService;
    private final ObjectProvider<MenuCacheService> menuCacheServiceProvider;
    private final ObjectProvider<com.ses.service.security.AuthorizationService> authorizationServiceProvider;
    private final ObjectProvider<EngineerCertificationMapper> engineerCertificationMapperProvider;
    private final java.time.Clock clock;

    /** 注文文書（SALES_ORDER link）のscope解決用。テストスライス互換のため任意注入。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.mapper.SalesOrderMapper salesOrderMapper;

    /** サービスリクエスト添付（SERVICE_REQUEST link）のscope解決用。テストスライス互換のため任意注入。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.mapper.ServiceRequestMapper serviceRequestMapper;

    /** SERVICE_REQUEST添付の参照集合。未取得時はfail-closedとする。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.service.impl.ServiceRequestFileReferenceProvider serviceRequestFileReferenceProvider;

    /** Portal入口以外からも添付リンクを再検証するためのmapper。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.mapper.ServiceAttachmentLinkMapper serviceAttachmentLinkMapper;

    public void assertDownloadAllowed(String storedName) {
        assertDownloadAllowed(storedName, null, null);
    }

    /**
     * 顧客ポータルのサービスリクエスト添付専用scope。
     * 内部メニュー権限へフォールバックせず、リクエスト顧客・文書ID・CLEAN版を同時に検証する。
     */
    public void assertPortalServiceRequestDownloadAllowed(String storedName, Long serviceRequestId,
                                                           Long customerId, Long documentId) {
        ServiceAttachmentLink link = serviceAttachmentLinkMapper == null ? null
                : serviceAttachmentLinkMapper.selectOne(new QueryWrapper<ServiceAttachmentLink>()
                        .eq("service_request_id", serviceRequestId).eq("document_id", documentId)
                        .last("LIMIT 1"));
        assertPortalServiceRequestDownloadAllowed(storedName, serviceRequestId, customerId, documentId, link);
    }

    /** Portal添付の全認可条件を単一のfail-closed境界で検証する。 */
    public void assertPortalServiceRequestDownloadAllowed(String storedName, Long serviceRequestId,
                                                           Long customerId, Long documentId,
                                                           ServiceAttachmentLink attachmentLink) {
        if (attachmentLink == null
                || !java.util.Objects.equals(attachmentLink.getServiceRequestId(), serviceRequestId)
                || !java.util.Objects.equals(attachmentLink.getDocumentId(), documentId)
                || !"PORTAL_VISIBLE".equals(attachmentLink.getVisibility())) {
            throw BusinessException.of(404, "error.notFound");
        }
        DocumentVersionMapper versionMapper = documentVersionMapperProvider.getIfAvailable();
        DocumentVersion version = versionMapper == null ? null : versionMapper.selectOne(
                new QueryWrapper<DocumentVersion>().eq("tenant_id", com.ses.service.accounting.AccountingTenantContextHolder.getCurrentTenantId())
                        .eq("storage_key", storedName)
                        .eq("document_id", documentId).last("LIMIT 1"));
        if (version == null || !java.util.Objects.equals(version.getDocumentId(), documentId)) {
            throw BusinessException.of(404, "error.notFound");
        }
        if (!"CLEAN".equals(version.getScanStatus())) {
            throw BusinessException.of(403, "error.file.scanNotReady");
        }
        if (serviceRequestMapper == null || serviceRequestId == null || customerId == null) {
            throw BusinessException.of(403, "error.forbidden");
        }
        String tenantId = com.ses.service.accounting.AccountingTenantContextHolder.getCurrentTenantId();
        ServiceRequest request = serviceRequestMapper.selectOne(new QueryWrapper<ServiceRequest>()
                .eq("tenant_id", tenantId).eq("id", serviceRequestId));
        if (request == null || !java.util.Objects.equals(request.getCustomerId(), customerId)) {
            throw BusinessException.of(404, "error.notFound");
        }

        com.ses.mapper.DocumentMapper documentMapper = documentMapperProvider.getIfAvailable();
        com.ses.entity.Document document = documentMapper == null ? null : documentMapper.selectOne(
                new QueryWrapper<com.ses.entity.Document>().eq("id", documentId).eq("tenant_id", tenantId));
        if (document == null || !"SERVICE_REQUEST_ATTACHMENT".equals(document.getDocumentType())
                || !java.util.Objects.equals(tenantId, document.getTenantId())
                || !java.util.Objects.equals(tenantId, version.getTenantId())) {
            throw BusinessException.of(404, "error.notFound");
        }
        if (Integer.valueOf(1).equals(document.getLegalHoldFlag())
                || (document.getRetentionUntil() != null
                && document.getRetentionUntil().isBefore(java.time.LocalDate.now(clock)))) {
            throw BusinessException.of(403, "error.file.legalHoldActive");
        }

        DocumentLinkMapper linkMapper = documentLinkMapperProvider.getIfAvailable();
        boolean typedLink = linkMapper != null && linkMapper.selectList(new QueryWrapper<DocumentLink>()
                        .eq("tenant_id", tenantId)
                        .eq("document_id", documentId)
                        .eq("target_type", "SERVICE_REQUEST")
                        .eq("target_id", serviceRequestId))
                .stream().anyMatch(link -> !Integer.valueOf(1).equals(link.getDeletedFlag()));
        if (!typedLink) {
            throw BusinessException.of(403, "error.forbidden");
        }
        if (serviceRequestFileReferenceProvider == null
                || serviceRequestFileReferenceProvider.referencedFileNames() == null
                || !serviceRequestFileReferenceProvider.referencedFileNames().contains(storedName)) {
            throw BusinessException.of(403, "error.forbidden");
        }
    }

    /** 資格証憑の専用認可境界。汎用文書リンク規則へフォールバックしない。 */
    public void assertCertificationEvidenceDownloadAllowed(String storedName, Long recordId,
                                                            Long documentId, Long expectedDocumentVersionId,
                                                            String expectedHash) {
        DocumentVersionMapper versionMapper = documentVersionMapperProvider.getIfAvailable();
        String tenantId = com.ses.service.accounting.AccountingTenantContextHolder.getCurrentTenantId();
        DocumentVersion version = versionMapper == null ? null : versionMapper.selectOne(
                new QueryWrapper<DocumentVersion>().eq("tenant_id", tenantId)
                        .eq("storage_key", storedName).last("LIMIT 1"));
        if (version == null || !Objects.equals(documentId, version.getDocumentId())
                || !"CLEAN".equals(version.getScanStatus())) {
            throw BusinessException.of(403, "error.file.scanNotReady");
        }

        com.ses.mapper.DocumentMapper documentMapper = documentMapperProvider.getIfAvailable();
        com.ses.entity.Document document = documentMapper == null
                ? null : documentMapper.selectOne(new QueryWrapper<com.ses.entity.Document>()
                .eq("id", version.getDocumentId()).eq("tenant_id", tenantId));
        if (document == null || !"CERTIFICATION_EVIDENCE".equals(document.getDocumentType())
                || !Objects.equals(tenantId, document.getTenantId())
                || !Objects.equals(tenantId, version.getTenantId())) {
            throw BusinessException.of(403, "error.forbidden");
        }
        if (Integer.valueOf(1).equals(document.getLegalHoldFlag())
                || (document.getRetentionUntil() != null
                && document.getRetentionUntil().isBefore(java.time.LocalDate.now(clock)))) {
            throw BusinessException.of(403, "error.file.legalHoldActive");
        }
        assertCertificationEvidenceAllowed(version, expectedDocumentVersionId, expectedHash, recordId);
    }

    /**
     * 資格証憑などexact document version/hash検証が必要な場合に使用する。
     *
     * @param expectedDocumentVersionId eventに保存したt_document_version.id（nullなら検証しない）
     * @param expectedHash eventに保存したSHA-256 hex（nullなら検証しない）
     */
    public void assertDownloadAllowed(String storedName, Long expectedDocumentVersionId, String expectedHash) {
        // 1. t_resume_ingestion の原本ファイル
        ResumeIngestion ingestion = resumeIngestionMapper.selectOne(
                new QueryWrapper<ResumeIngestion>().eq("stored_file_name", storedName).last("LIMIT 1"));
        if (ingestion != null) {
            assertMenuAllowed("resume-ingestion");
            return;
        }

        // 2. t_engineer の顔写真 (photo_url)
        Engineer engineer = engineerMapper.selectOne(
                new QueryWrapper<Engineer>().eq("photo_url", storedName).last("LIMIT 1"));
        if (engineer != null) {
            dataScopeService.assertAllowedEngineer(engineer.getId());
            return;
        }

        // 3. t_proposal のスキルシート
        Proposal proposal = proposalMapper.selectOne(
                new QueryWrapper<Proposal>().eq("skill_sheet_path", storedName).last("LIMIT 1"));
        if (proposal != null) {
            dataScopeService.assertAllowedProposal(proposal.getId());
            return;
        }

        // 4. t_project_ingestion の原本
        ProjectIngestion projectIngestion = projectIngestionMapper.selectOne(
                new QueryWrapper<ProjectIngestion>().eq("stored_file_name", storedName).last("LIMIT 1"));
        if (projectIngestion != null) {
            assertMenuAllowed("project-ingestion");
            return;
        }

        // 5. t_bp_availability_ingestion の原本
        BpAvailabilityIngestion bpIngestion = bpAvailabilityIngestionMapper.selectOne(
                new QueryWrapper<BpAvailabilityIngestion>().eq("stored_file_name", storedName).last("LIMIT 1"));
        if (bpIngestion != null) {
            assertMenuAllowed("bp-availability-ingestion");
            return;
        }

        // 6. t_document_version の法定文書台帳ファイル (R5.2 & R5.3)
        DocumentVersionMapper versionMapper = documentVersionMapperProvider.getIfAvailable();
            DocumentVersion documentVersion = versionMapper != null
                ? versionMapper.selectOne(new QueryWrapper<DocumentVersion>()
                        .eq("tenant_id", currentTenant())
                        .eq("storage_key", storedName).last("LIMIT 1"))
                : null;
        if (documentVersion != null) {
            // P1-02: scan未完了・拒否はfail-closedで拒否 (CLEAN 以外は不可)
            String scanStatus = documentVersion.getScanStatus();
            if (scanStatus == null || !"CLEAN".equals(scanStatus)) {
                throw BusinessException.of(403, "error.file.scanNotReady");
            }

            // S14 (engineer-self-service-portal-v2): 文書種別ごとの専用規則（decision table §6.2）。
            // PRIVATE_NOTE（1on1 confidential相談）はHR/明示権限割当管理者のみ。RECEIPT（経費領収書）は
            // 本人/管理者/マネージャー（配下）のみで、営業・HRは不可視（給与・経費は営業不可視）。
            // CHANGE_REQUEST_ATTACHMENT（変更申請添付）は本人/HR/管理者/マネージャー（組織scope∩DataScope）のみ。
            String documentType = documentTypeOf(documentVersion.getDocumentId());
            // 管理レポートはrecipient deliveryのtoken/期限/再認証/scopeを必須とし、
            // 汎用文書台帳downloadからの迂回を許可しない。
            if ("MANAGEMENT_REPORT".equals(documentType)) {
                throw BusinessException.of(403, "error.managementReport.deliveryRequired");
            }
            if ("PRIVATE_NOTE".equals(documentType)) {
                String role = SecurityUtils.currentRole();
                if ("HR".equals(role)) {
                    return; // HR は全件可視
                }
                // 管理者でも one-on-one.confidential 権限グループ未割当は拒否（R1-P1-07）。
                // bean不在・判定例外は AuthorizationService.isAllowed が fail-closed で false を返す。
                com.ses.service.security.AuthorizationService authorizationService =
                        authorizationServiceProvider.getIfAvailable();
                org.springframework.security.core.Authentication auth =
                        org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                if (authorizationService != null && authorizationService.isAllowed(auth, "one-on-one.confidential")) {
                    return;
                }
                throw BusinessException.of(403, "error.forbidden");
            }
            if ("RECEIPT".equals(documentType)) {
                Long engineerId = linkedEngineerId(documentVersion.getDocumentId());
                if (engineerId == null || !canViewReceipt(engineerId)) {
                    throw BusinessException.of(403, "error.forbidden");
                }
                return;
            }
            if ("CHANGE_REQUEST_ATTACHMENT".equals(documentType)) {
                String role = SecurityUtils.currentRole();
                if ("HR".equals(role) || "管理者".equals(role)) {
                    return; // HR/管理者は全件可視（decision table §6.2）
                }
                Long fileEngineerId = linkedEngineerId(documentVersion.getDocumentId());
                if ("マネージャー".equals(role)) {
                    if (fileEngineerId != null && isEngineerInManagerScope(fileEngineerId)) {
                        return;
                    }
                    throw BusinessException.of(403, "error.forbidden");
                }
                if ("要員".equals(role)) {
                    com.ses.service.EngineerAccountLinkService linkService =
                            engineerAccountLinkServiceProvider.getIfAvailable();
                    Long ownEngineerId = linkService == null
                            ? null : linkService.findEngineerIdByUserId(SecurityUtils.currentUserId());
                    if (fileEngineerId != null && fileEngineerId.equals(ownEngineerId)) {
                        return;
                    }
                }
                // 営業・その他・本人以外は不可視
                throw BusinessException.of(403, "error.forbidden");
            }
            if ("CERTIFICATION_EVIDENCE".equals(documentType)) {
                // 資格証憑は保持中のdownload/exportを許可しない契約。汎用文書台帳の
                // legal hold（通常は廃棄だけを止める）より厳しい専用境界を先に適用する。
                com.ses.mapper.DocumentMapper documentMapper = documentMapperProvider.getIfAvailable();
                com.ses.entity.Document document = findDocumentForCurrentTenant(documentMapper,
                        documentVersion.getDocumentId());
                if (document == null || Integer.valueOf(1).equals(document.getLegalHoldFlag())
                        || (document.getRetentionUntil() != null
                        && document.getRetentionUntil().isBefore(java.time.LocalDate.now(clock)))) {
                    throw BusinessException.of(403, "error.file.legalHoldActive");
                }
                assertCertificationEvidenceAllowed(documentVersion, expectedDocumentVersionId, expectedHash, null);
                return;
            }

            // P1-03: メニュー権限判定
            assertMenuAllowed("document-archive");

            // P1-03 / P0-01 / P2-01: t_document_link 経由の DataScope 条件判定（管理者全許可、営業は和集合）
            org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            boolean isAdmin = auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_管理者".equals(a.getAuthority()));
            if (!isAdmin) {
                DocumentLinkMapper linkMapper = documentLinkMapperProvider.getIfAvailable();
                if (linkMapper != null) {
                    List<DocumentLink> links = linkMapper.selectList(
                            new QueryWrapper<DocumentLink>()
                                    .eq("tenant_id", currentTenant())
                                    .eq("document_id", documentVersion.getDocumentId()));
                    if (!links.isEmpty()) {
                        boolean anyAllowed = false;
                        for (DocumentLink link : links) {
                            try {
                                String type = link.getTargetType();
                                Long targetId = link.getTargetId();
                                if ("CUSTOMER".equals(type)) {
                                    dataScopeService.assertAllowedCustomer(targetId);
                                    anyAllowed = true;
                                    break;
                                } else if ("ENGINEER".equals(type)) {
                                    dataScopeService.assertAllowedEngineer(targetId);
                                    anyAllowed = true;
                                    break;
                                } else if ("CONTRACT".equals(type)) {
                                    dataScopeService.assertAllowedContract(targetId);
                                    anyAllowed = true;
                                    break;
                                } else if ("PROJECT".equals(type)) {
                                    dataScopeService.assertAllowedProject(targetId);
                                    anyAllowed = true;
                                    break;
                                } else if ("PROPOSAL".equals(type)) {
                                    dataScopeService.assertAllowedProposal(targetId);
                                    anyAllowed = true;
                                    break;
                                } else if ("SALES_ORDER".equals(type)) {
                                    // 注文書原本・注文請書は注文一覧と同じscope（顧客DataScope）で見せる
                                    com.ses.entity.SalesOrder salesOrder = salesOrderMapper == null ? null
                                            : salesOrderMapper.selectById(targetId);
                                    if (salesOrder != null) {
                                        dataScopeService.assertAllowedCustomer(salesOrder.getCustomerId());
                                        anyAllowed = true;
                                        break;
                                    }
                                } else if ("SERVICE_REQUEST".equals(type)) {
                                    // サービスリクエスト添付はリクエストの顧客DataScopeで見せる (P1-03)
                                    com.ses.entity.ServiceRequest sr = serviceRequestMapper == null ? null
                                            : serviceRequestMapper.selectById(targetId);
                                    if (sr != null) {
                                        dataScopeService.assertAllowedCustomer(sr.getCustomerId());
                                        anyAllowed = true;
                                        break;
                                    }
                                }
                                // 未対応・未定義のターゲットタイプは評価せず次のリンクへ（fail-closed）
                            } catch (BusinessException ignored) {
                                // 個別の評価で不可の場合は次のリンクへ（和集合）
                            }
                        }
                        if (!anyAllowed) {
                            throw BusinessException.of(403, "error.forbidden");
                        }
                    }
                }
            }
            return;
        }

        // 未登録・未知のstoredNameは拒否（fail-closed）
        throw BusinessException.of(403, "error.file.unknownReference");
    }

    private void assertMenuAllowed(String menuKey) {
        String role = SecurityUtils.currentRole();
        if ("管理者".equals(role)) {
            return;
        }
        MenuCacheService menuCacheService = menuCacheServiceProvider.getIfAvailable();
        if (menuCacheService == null || role == null
                || !menuCacheService.getMenuKeysByRole(role).contains(menuKey)) {
            throw BusinessException.of(403, "error.forbidden");
        }
    }

    /** 文書のdocument_typeを解決する（不変条件violation時はfail-closedでnull）。 */
    private String documentTypeOf(Long documentId) {
        com.ses.mapper.DocumentMapper documentMapper = documentMapperProvider.getIfAvailable();
        if (documentMapper == null || documentId == null) {
            return null;
        }
        com.ses.entity.Document document = findDocumentForCurrentTenant(documentMapper, documentId);
        return document == null ? null : document.getDocumentType();
    }

    /** 現在tenantの文書だけを取得する。旧default fixtureのNULL tenantのみ互換扱いする。 */
    private com.ses.entity.Document findDocumentForCurrentTenant(
            com.ses.mapper.DocumentMapper documentMapper, Long documentId) {
        String tenantId = currentTenant();
        com.ses.entity.Document document = documentMapper.selectOne(new QueryWrapper<com.ses.entity.Document>()
                .eq("id", documentId).eq("tenant_id", tenantId));
        if (document == null && "default".equals(tenantId)) {
            com.ses.entity.Document legacy = documentMapper.selectById(documentId);
            if (legacy != null && legacy.getTenantId() == null) {
                document = legacy;
            }
        }
        return document;
    }

    /** 文書のENGINEER linkから要員IDを解決する（複数あれば先頭。無ければnull）。 */
    private Long linkedEngineerId(Long documentId) {
        DocumentLinkMapper linkMapper = documentLinkMapperProvider.getIfAvailable();
        if (linkMapper == null || documentId == null) {
            return null;
        }
        return linkMapper.selectList(new QueryWrapper<DocumentLink>()
                        .eq("tenant_id", currentTenant())
                        .eq("document_id", documentId).eq("target_type", "ENGINEER").last("LIMIT 1"))
                .stream().map(DocumentLink::getTargetId).findFirst().orElse(null);
    }

    /** 経費領収書の閲覧可否: 本人 / 管理者 / マネージャー（組織scope ∩ DataScope の配下）。 */
    private boolean canViewReceipt(Long engineerId) {
        String role = SecurityUtils.currentRole();
        if ("管理者".equals(role)) {
            return true;
        }
        if ("要員".equals(role)) {
            com.ses.service.EngineerAccountLinkService linkService = engineerAccountLinkServiceProvider.getIfAvailable();
            Long ownEngineerId = linkService == null ? null : linkService.findEngineerIdByUserId(SecurityUtils.currentUserId());
            return engineerId.equals(ownEngineerId);
        }
        if ("マネージャー".equals(role)) {
            return isEngineerInManagerScope(engineerId);
        }
        // 営業・HR・その他はdecision table §6.2により不可視（給与・経費は営業不可視）
        return false;
    }

    /**
     * 資格証憑（CERTIFICATION_EVIDENCE）の専用scope。
     * typed {@code CERTIFICATION_RECORD} linkのみを認可根拠とし、管理者bypass・empty-link・
     * ENGINEER-only mixed linkを拒否する（design §3.6）。
     */
    private void assertCertificationEvidenceAllowed(DocumentVersion documentVersion,
                                                    Long expectedDocumentVersionId,
                                                    String expectedHash,
                                                    Long expectedRecordId) {
        if (expectedDocumentVersionId != null && !expectedDocumentVersionId.equals(documentVersion.getId())) {
            throw BusinessException.of(403, "error.file.versionMismatch");
        }
        if (expectedHash != null && !expectedHash.equalsIgnoreCase(documentVersion.getSha256())) {
            throw BusinessException.of(403, "error.file.hashMismatch");
        }

        DocumentLinkMapper linkMapper = documentLinkMapperProvider.getIfAvailable();
        EngineerCertificationMapper certificationMapper = engineerCertificationMapperProvider.getIfAvailable();
        if (linkMapper == null || certificationMapper == null) {
            throw BusinessException.of(403, "error.forbidden");
        }

        List<DocumentLink> links = linkMapper.selectList(
                new QueryWrapper<DocumentLink>().eq("tenant_id",
                                com.ses.service.accounting.AccountingTenantContextHolder.getCurrentTenantId())
                        .eq("document_id", documentVersion.getDocumentId()));
        List<DocumentLink> certificationLinks = links.stream()
                .filter(link -> "CERTIFICATION_RECORD".equals(link.getTargetType())
                        && (expectedRecordId == null || Objects.equals(expectedRecordId, link.getTargetId())))
                .toList();
        if (certificationLinks.isEmpty()) {
            throw BusinessException.of(403, "error.forbidden");
        }

        boolean anyAllowed = false;
        for (DocumentLink link : certificationLinks) {
            try {
                EngineerCertification record = certificationMapper.selectOne(
                        new QueryWrapper<EngineerCertification>()
                                .eq("id", link.getTargetId())
                                .eq("tenant_id", currentTenant()));
                if (record == null && "default".equals(currentTenant())) {
                    // 旧unit fixtureにはtenant列を設定していない行があるため、NULLのみ互換扱いする。
                    EngineerCertification legacy = certificationMapper.selectById(link.getTargetId());
                    if (legacy != null && legacy.getTenantId() == null) {
                        record = legacy;
                    }
                }
                if (record == null) {
                    continue;
                }
                if (!Objects.equals(record.getTenantId(), documentVersion.getTenantId())
                        && record.getTenantId() != null) {
                    continue;
                }
                dataScopeService.assertAllowedEngineer(record.getEngineerId());
                anyAllowed = true;
                break;
            } catch (BusinessException ignored) {
                // generic ENGINEER link等は評価せず、typed linkのみで判定（mixed link対策）
            }
        }
        if (!anyAllowed) {
            throw BusinessException.of(403, "error.forbidden");
        }
    }

    private String currentTenant() {
        return com.ses.service.accounting.AccountingTenantContextHolder.getCurrentTenantId();
    }

    /** マネージャー（組織scope ∩ DataScope）の配下か判定する。 */
    private boolean isEngineerInManagerScope(Long engineerId) {
        com.ses.service.security.OrganizationScopeService scopeService = organizationScopeServiceProvider.getIfAvailable();
        if (scopeService == null) {
            return false;
        }
        if (scopeService.hasFullAccess()) {
            return true;
        }
        java.util.Set<Long> allowed = scopeService.allowedEngineerIds(java.time.LocalDate.now(clock));
        return allowed != null && allowed.contains(engineerId);
    }
}
