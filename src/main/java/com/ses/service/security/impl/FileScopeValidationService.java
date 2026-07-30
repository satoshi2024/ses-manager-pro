package com.ses.service.security.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.BpAvailabilityIngestion;
import com.ses.entity.DocumentLink;
import com.ses.entity.DocumentVersion;
import com.ses.entity.Engineer;
import com.ses.entity.ProjectIngestion;
import com.ses.entity.Proposal;
import com.ses.entity.ResumeIngestion;
import com.ses.mapper.BpAvailabilityIngestionMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentVersionMapper;
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
    private final DataScopeService dataScopeService;
    private final ObjectProvider<MenuCacheService> menuCacheServiceProvider;

    public void assertDownloadAllowed(String storedName) {
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

            // P1-03: メニュー権限判定
            assertMenuAllowed("document-archive");

            // P1-03: t_document_link 経由の DataScope 条件判定（和集合: いずれか1つでも許可されれば可）
            DocumentLinkMapper linkMapper = documentLinkMapperProvider.getIfAvailable();
            if (linkMapper != null) {
                List<DocumentLink> links = linkMapper.selectList(
                        new QueryWrapper<DocumentLink>().eq("document_id", documentVersion.getDocumentId()));
                if (!links.isEmpty()) {
                    boolean anyAllowed = false;
                    for (DocumentLink link : links) {
                        try {
                            if ("CUSTOMER".equals(link.getTargetType())) {
                                dataScopeService.assertAllowedCustomer(link.getTargetId());
                                anyAllowed = true;
                                break;
                            } else if ("ENGINEER".equals(link.getTargetType())) {
                                dataScopeService.assertAllowedEngineer(link.getTargetId());
                                anyAllowed = true;
                                break;
                            } else {
                                // 他のターゲットタイプ（CONTRACT等）はデフォルトで許可対象
                                anyAllowed = true;
                                break;
                            }
                        } catch (BusinessException ignored) {
                            // 個別の評価で不可の場合は次のリンクへ（和集合）
                        }
                    }
                    if (!anyAllowed) {
                        throw BusinessException.of(403, "error.forbidden");
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
}
