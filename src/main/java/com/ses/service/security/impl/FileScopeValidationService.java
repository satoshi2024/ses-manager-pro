package com.ses.service.security.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.BpAvailabilityIngestion;
import com.ses.entity.Engineer;
import com.ses.entity.ProjectIngestion;
import com.ses.entity.Proposal;
import com.ses.entity.ResumeIngestion;
import com.ses.entity.DocumentVersion;
import com.ses.mapper.BpAvailabilityIngestionMapper;
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

/**
 * ファイルダウンロード時のアクセス制御（A8-04）を行うサービス。
 *
 * <p><b>ファイルを保存する機能を追加したら、必ずここに判定を1つ足すこと。</b>
 * 未登録・未知のstoredNameは error.file.unknownReference で拒否する（fail-closed）。
 * ファイル保存機能を追加した際の判定追加漏れは正当なダウンロードが403拒否となる。
 * 孤児清理の {@code FileReferenceProvider} と対で必要になる点に注意。
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
    private final DataScopeService dataScopeService;
    /** ObjectProvider 経由。テストスライスで未配線でも生成に失敗させない（その場合は管理者以外を拒否）。 */
    private final ObjectProvider<MenuCacheService> menuCacheServiceProvider;

    public void assertDownloadAllowed(String storedName) {
        // 1. t_resume_ingestion の原本ファイル
        ResumeIngestion ingestion = resumeIngestionMapper.selectOne(
                new QueryWrapper<ResumeIngestion>().eq("stored_file_name", storedName).last("LIMIT 1"));
        if (ingestion != null) {
            String role = SecurityUtils.currentRole();
            if (!"管理者".equals(role) && !"HR".equals(role)) {
                throw BusinessException.of(403, "error.forbidden");
            }
            return;
        }

        // 2. t_engineer の写真
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

        // 4. t_project_ingestion の原本(eml/貼付元)。案件メールは送信元や担当者名を含む。
        ProjectIngestion projectIngestion = projectIngestionMapper.selectOne(
                new QueryWrapper<ProjectIngestion>().eq("stored_file_name", storedName).last("LIMIT 1"));
        if (projectIngestion != null) {
            assertMenuAllowed("project-ingestion");
            return;
        }

        // 5. t_bp_availability_ingestion の原本。要員の経歴・連絡先を含むためレジュメ取込と同格に扱う。
        BpAvailabilityIngestion bpIngestion = bpAvailabilityIngestionMapper.selectOne(
                new QueryWrapper<BpAvailabilityIngestion>().eq("stored_file_name", storedName).last("LIMIT 1"));
        if (bpIngestion != null) {
            assertMenuAllowed("bp-availability-ingestion");
            return;
        }

        // 6. t_document_version の法定文書台帳ファイル
        DocumentVersion documentVersion = documentVersionMapperProvider.getIfAvailable() != null
                ? documentVersionMapperProvider.getIfAvailable().selectOne(
                    new QueryWrapper<DocumentVersion>().eq("storage_key", storedName).last("LIMIT 1"))
                : null;
        if (documentVersion != null) {
            // scan未完了・拒否はfail-closedで拒否
            String scanStatus = documentVersion.getScanStatus();
            if (scanStatus == null || "PENDING".equals(scanStatus) || "REJECTED".equals(scanStatus)) {
                throw BusinessException.of(403, "error.file.scanNotReady");
            }
            assertMenuAllowed("document-archive");
            return;
        }

        // 未登録・未知のstoredNameは、URLを知っているだけの認証済みユーザーへ公開しない。
        throw BusinessException.of(403, "error.file.unknownReference");
    }

    /** 対象メニューを閲覧できるロールか（管理者は常に可。MenuPermissionFilter と同じ判定）。 */
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
