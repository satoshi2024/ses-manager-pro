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

    public void assertDownloadAllowed(String storedName) {
        assertDownloadAllowed(storedName, null, null);
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
                ? versionMapper.selectOne(new QueryWrapper<DocumentVersion>().eq("storage_key", storedName).last("LIMIT 1"))
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
                com.ses.entity.Document document = documentMapper == null
                        ? null : documentMapper.selectById(documentVersion.getDocumentId());
                if (document == null || Integer.valueOf(1).equals(document.getLegalHoldFlag())) {
                    throw BusinessException.of(403, "error.file.legalHoldActive");
                }
                assertCertificationEvidenceAllowed(documentVersion, expectedDocumentVersionId, expectedHash);
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
                            new QueryWrapper<DocumentLink>().eq("document_id", documentVersion.getDocumentId()));
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
        com.ses.entity.Document document = documentMapper.selectById(documentId);
        return document == null ? null : document.getDocumentType();
    }

    /** 文書のENGINEER linkから要員IDを解決する（複数あれば先頭。無ければnull）。 */
    private Long linkedEngineerId(Long documentId) {
        DocumentLinkMapper linkMapper = documentLinkMapperProvider.getIfAvailable();
        if (linkMapper == null || documentId == null) {
            return null;
        }
        return linkMapper.selectList(new QueryWrapper<DocumentLink>()
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
                                                    String expectedHash) {
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
                new QueryWrapper<DocumentLink>().eq("document_id", documentVersion.getDocumentId()));
        List<DocumentLink> certificationLinks = links.stream()
                .filter(link -> "CERTIFICATION_RECORD".equals(link.getTargetType()))
                .toList();
        if (certificationLinks.isEmpty()) {
            throw BusinessException.of(403, "error.forbidden");
        }

        boolean anyAllowed = false;
        for (DocumentLink link : certificationLinks) {
            try {
                EngineerCertification record = certificationMapper.selectById(link.getTargetId());
                if (record == null) {
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
