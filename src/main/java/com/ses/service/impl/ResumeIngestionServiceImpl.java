package com.ses.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.enums.FileKind;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.config.AiConfig;
import com.ses.dto.document.DocumentRegisterRequest;
import com.ses.dto.file.StoredFile;
import com.ses.dto.resume.ParsedResumeDto;
import com.ses.dto.resume.ReviewedResumeDto;
import com.ses.dto.skill.SkillReplaceRequest;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerCareer;
import com.ses.entity.EngineerSkill;
import com.ses.entity.ResumeIngestion;
import com.ses.mapper.ResumeIngestionMapper;
import com.ses.service.CandidateService;
import com.ses.service.DocumentService;
import com.ses.service.DocumentTextExtractor;
import com.ses.service.EngineerCareerService;
import com.ses.service.EngineerService;
import com.ses.service.EngineerSkillService;
import com.ses.service.FileStorageService;
import com.ses.service.ResumeIngestionService;
import com.ses.service.SkillTagResolver;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.ai.ResumeParseService;
import com.ses.service.security.CandidateOwnershipResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/** スキルシート取込。tenant境界と状態CASを全て専用mapperへ集約する。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeIngestionServiceImpl extends com.baomidou.mybatisplus.extension.service.impl.ServiceImpl<ResumeIngestionMapper, ResumeIngestion>
        implements ResumeIngestionService {

    private static final String STATUS_PENDING = "取込待ち";
    private static final String STATUS_PARSING = "抽出中";
    private static final String STATUS_REVIEW = "要確認";
    private static final String STATUS_DONE = "確定済";
    private static final String STATUS_REJECTED = "却下";
    private static final String STATUS_FAILED = "失敗";

    private final FileStorageService fileStorageService;
    private final DocumentTextExtractor documentTextExtractor;
    private final ResumeParseService resumeParseService;
    private final EngineerService engineerService;
    private final EngineerSkillService engineerSkillService;
    private final EngineerCareerService engineerCareerService;
    private final SkillTagResolver skillTagResolver;
    private final CandidateService candidateService;
    private final AiConfig aiConfig;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<ResumeIngestionService> selfProvider;
    private final ObjectProvider<DocumentService> documentServiceProvider;
    private final CandidateOwnershipResolver candidateOwnershipResolver;

    @Override
    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<ResumeIngestion> pageForCurrentTenant(
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<ResumeIngestion> page, String status) {
        return baseMapper.selectPageForTenant(page, requireTenant(), status);
    }

    @Override
    public ResumeIngestion getForCurrentTenant(Long id) {
        return baseMapper.selectByIdForTenant(id, requireTenant());
    }

    @Override
    public ResumeIngestion createJob(MultipartFile file, Long candidateId) {
        String tenantId = requireTenant();
        if (candidateId != null && (candidateOwnershipResolver == null
                || candidateOwnershipResolver.select(tenantId, candidateId) == null)) {
            throw BusinessException.of(404, "error.candidate.notFound");
        }
        StoredFile stored = fileStorageService.store(file, FileKind.SKILL_SHEET);
        ResumeIngestion job = new ResumeIngestion();
        job.setTenantId(tenantId);
        job.setOriginalFileName(stored.getOriginalName());
        job.setStoredFileName(stored.getStoredName());
        job.setFileExt(extensionOf(stored.getStoredName()));
        job.setStatus(STATUS_PENDING);
        job.setCandidateId(candidateId);
        job.setVersion(0);
        if (!this.save(job)) {
            deleteStoredFileQuietly(stored);
            throw BusinessException.of("error.resume.saveFailed");
        }

        DocumentService documentService = documentServiceProvider == null
                ? null : documentServiceProvider.getIfAvailable();
        if (documentService == null) {
            baseMapper.deleteNewJobForTenant(job.getId(), tenantId, versionOf(job));
            deleteStoredFileQuietly(stored);
            throw BusinessException.of(503, "error.document.unavailable");
        }
        try (java.io.InputStream original = fileStorageService.load(stored.getStoredName()).getInputStream()) {
            documentService.registerReceived(DocumentRegisterRequest.builder()
                    .tenantId(tenantId)
                    .documentType("RESUME_INGESTION")
                    .title(stored.getOriginalName())
                    .sourceType("RECEIVED")
                    .businessKey("RESUME_INGESTION:" + job.getId())
                    .versionDiscriminator("original")
                    .originalName(stored.getOriginalName())
                    .contentType(file.getContentType())
                    .targetType("RESUME_INGESTION")
                    .targetId(job.getId())
                    .createdBy(SecurityUtils.currentUserId())
                    .build(), original);
        } catch (Exception e) {
            baseMapper.deleteNewJobForTenant(job.getId(), tenantId, versionOf(job));
            deleteStoredFileQuietly(stored);
            throw e instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("原本の文書台帳登録に失敗しました", e);
        }

        log.info("スキルシート取込ジョブを作成しました: jobId={}, fileName={}", job.getId(), stored.getOriginalName());
        ResumeIngestionService self = selfProvider == null ? null : selfProvider.getIfAvailable();
        if (self != null) {
            self.parseAsync(job.getId(), tenantId);
        }
        return job;
    }

    @Override
    @Async("taskExecutor")
    public void parseAsync(Long id) {
        parseAsyncInTenant(id, requireTenant());
    }

    @Override
    @Async("taskExecutor")
    public void parseAsync(Long id, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw BusinessException.of(403, "error.tenant.contextRequired");
        }
        AccountingTenantContextHolder.runWithTenant(tenantId, () -> parseAsyncInTenant(id, tenantId));
    }

    private void parseAsyncInTenant(Long id, String tenantId) {
        ResumeIngestion job = baseMapper.selectByIdForTenant(id, tenantId);
        if (job == null) {
            log.error("ジョブが見つかりません: id={}", id);
            return;
        }
        Integer expected = versionOf(job);
        boolean casOk = casStatus(id, tenantId, STATUS_PENDING, STATUS_PARSING, expected);
        if (!casOk) {
            job = baseMapper.selectByIdForTenant(id, tenantId);
            casOk = job != null && casStatus(id, tenantId, STATUS_REVIEW, STATUS_PARSING, versionOf(job));
        }
        if (!casOk) {
            job = baseMapper.selectByIdForTenant(id, tenantId);
            casOk = job != null && casStatus(id, tenantId, STATUS_FAILED, STATUS_PARSING, versionOf(job));
        }
        if (!casOk) {
            log.warn("状態を抽出中へ遷移できませんでした: id={}", id);
            return;
        }

        try {
            String text = documentTextExtractor.extract(job.getStoredFileName(), job.getFileExt());
            if (text == null || text.isBlank()) {
                updateFailed(id, tenantId, "テキスト抽出に失敗しました。画像 PDF または空ファイルの可能性があります。");
                return;
            }
            ParsedResumeDto parsed = resumeParseService.parse(text);
            String parsedJson = objectMapper.writeValueAsString(parsed);
            ResumeIngestion parsingJob = baseMapper.selectByIdForTenant(id, tenantId);
            int updated = parsingJob == null ? 0 : baseMapper.saveParsedForTenant(id, tenantId, STATUS_REVIEW,
                    text, parsedJson, aiConfig.getProvider(), aiConfig.getModel(), versionOf(parsingJob));
            if (updated != 1) {
                throw BusinessException.of(409, "error.common.optimisticLock");
            }
            log.info("スキルシート解析完了: jobId={}", id);
        } catch (BusinessException e) {
            log.error("スキルシート解析失敗: jobId={}, msg={}", id, e.getMessage());
            updateFailed(id, tenantId, e.getMessage());
        } catch (Exception e) {
            log.error("スキルシート解析失敗（予期しないエラー）: jobId={}", id, e);
            updateFailed(id, tenantId, "内部エラーが発生しました。");
        }
    }

    @Override
    public void reparse(Long id) {
        ResumeIngestion job = getJobOrThrow(id);
        if (!STATUS_REVIEW.equals(job.getStatus()) && !STATUS_FAILED.equals(job.getStatus())) {
            throw BusinessException.of("error.resume.invalidStatus");
        }
        ResumeIngestionService self = selfProvider == null ? null : selfProvider.getIfAvailable();
        if (self != null) {
            self.parseAsync(id, requireTenant());
        }
    }

    @Override
    public void saveReview(Long id, ReviewedResumeDto dto) {
        ResumeIngestion job = getJobOrThrow(id);
        if (!STATUS_REVIEW.equals(job.getStatus())) {
            throw BusinessException.of("error.resume.invalidStatus");
        }
        try {
            String parsedJson = objectMapper.writeValueAsString(dto);
            if (baseMapper.saveReviewForTenant(id, requireTenant(), parsedJson, dto.getReviewNote(), versionOf(job)) != 1) {
                throw BusinessException.of(409, "error.common.optimisticLock");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("レビュー保存に失敗しました: jobId={}", id, e);
            throw BusinessException.of("error.systemError");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long confirm(Long id, ReviewedResumeDto dto) {
        String tenantId = requireTenant();
        ResumeIngestion job = getJobOrThrow(id);
        if (job.getConvertedEngineerId() != null) {
            throw BusinessException.of(409, "error.resume.alreadyConfirmed");
        }
        if (!STATUS_REVIEW.equals(job.getStatus())) {
            throw BusinessException.of("error.resume.invalidStatus");
        }
        if (job.getCandidateId() != null && (candidateOwnershipResolver == null
                || candidateOwnershipResolver.select(tenantId, job.getCandidateId()) == null)) {
            throw BusinessException.of(404, "error.candidate.notFound");
        }

        ReviewedResumeDto.EngineerPart ep = dto.getEngineer();
        if (ep == null || ep.getFullName() == null || ep.getFullName().isBlank()) {
            throw BusinessException.of("error.engineer.nameRequired");
        }
        Engineer engineer = new Engineer();
        engineer.setTenantId(tenantId);
        engineer.setVersion(0);
        engineer.setFullName(ep.getFullName());
        engineer.setFullNameKana(ep.getFullNameKana());
        engineer.setInitialName(ep.getInitialName());
        engineer.setGender(ep.getGender());
        engineer.setBirthDate(ep.getBirthDate());
        engineer.setNationality(ep.getNationality());
        engineer.setNearestStation(ep.getNearestStation());
        engineer.setPrefecture(ep.getPrefecture());
        engineer.setRailwayCompany(ep.getRailwayCompany());
        engineer.setEmploymentType(ep.getEmploymentType() != null ? ep.getEmploymentType() : "BP");
        engineer.setStatus("Bench");
        engineer.setExpectedUnitPrice(ep.getExpectedUnitPrice());
        engineer.setAvailableDate(ep.getAvailableDate());
        engineer.setExperienceYears(ep.getExperienceYears());
        engineer.setJapaneseLevel(ep.getJapaneseLevel());
        engineer.setResumeSummary(ep.getResumeSummary());
        com.ses.common.util.EntityProtectUtil.protectForCreate(engineer);
        engineerService.save(engineer);
        Long engineerId = engineer.getId();

        if (dto.getSkills() != null && !dto.getSkills().isEmpty()) {
            List<SkillReplaceRequest.SkillItem> items = new ArrayList<>();
            for (ReviewedResumeDto.SkillPart sp : dto.getSkills()) {
                if (sp.getName() == null || sp.getName().isBlank()) {
                    continue;
                }
                Long skillId = skillTagResolver.resolveOrCreate(sp.getName());
                SkillReplaceRequest.SkillItem item = new SkillReplaceRequest.SkillItem();
                item.setSkillId(skillId);
                item.setProficiency(sp.getProficiency());
                item.setExperienceYears(sp.getExperienceYears());
                items.add(item);
            }
            if (!items.isEmpty()) {
                SkillReplaceRequest skillRequest = new SkillReplaceRequest();
                skillRequest.setExpectedVersion(0);
                skillRequest.setReason("スキルシート取込確定");
                skillRequest.setSkills(items);
                engineerSkillService.replaceSkills(engineerId, skillRequest);
            }
        }

        if (dto.getCareers() != null) {
            for (ReviewedResumeDto.CareerPart cp : dto.getCareers()) {
                if (cp.getPeriodTo() != null && cp.getPeriodFrom() != null
                        && cp.getPeriodTo().isBefore(cp.getPeriodFrom())) {
                    continue;
                }
                EngineerCareer career = new EngineerCareer();
                career.setEngineerId(engineerId);
                career.setPeriodFrom(cp.getPeriodFrom());
                career.setPeriodTo(cp.getPeriodTo());
                career.setProjectName(cp.getProjectName());
                career.setClientIndustry(cp.getClientIndustry());
                career.setRole(cp.getRole());
                career.setTechStack(cp.getTechStack());
                career.setDescription(cp.getDescription());
                career.setTeamSize(cp.getTeamSize());
                engineerCareerService.save(career);
            }
        }

        if (baseMapper.confirmForTenant(id, tenantId, engineerId, dto.getReviewNote(), versionOf(job)) != 1) {
            throw BusinessException.of(409, "error.resume.alreadyConfirmed");
        }
        if (job.getCandidateId() != null) {
            // candidate link failure is part of the same transaction; never report a partial confirmation.
            candidateService.linkConvertedEngineer(job.getCandidateId(), engineerId);
        }
        return engineerId;
    }

    @Override
    public void reject(Long id, String reason) {
        ResumeIngestion job = getJobOrThrow(id);
        if (baseMapper.rejectForTenant(id, requireTenant(), reason, versionOf(job)) != 1) {
            throw BusinessException.of(409, "error.resume.invalidStatus");
        }
        log.info("スキルシート取込を却下しました: id={}", id);
    }

    private ResumeIngestion getJobOrThrow(Long id) {
        ResumeIngestion job = baseMapper.selectByIdForTenant(id, requireTenant());
        if (job == null) {
            throw BusinessException.of(404, "error.resume.notFound");
        }
        return job;
    }

    private boolean casStatus(Long id, String tenantId, String fromStatus, String toStatus, Integer expectedVersion) {
        return baseMapper.casStatusForTenant(id, tenantId, fromStatus, toStatus, expectedVersion) == 1;
    }

    private void updateFailed(Long id, String tenantId, String message) {
        ResumeIngestion job = baseMapper.selectByIdForTenant(id, tenantId);
        if (job != null) {
            baseMapper.updateFailedForTenant(id, tenantId,
                    message != null && message.length() > 500 ? message.substring(0, 500) : message,
                    versionOf(job));
        }
    }

    private String requireTenant() {
        return AccountingTenantContextHolder.requireTenantContext();
    }

    private int versionOf(ResumeIngestion job) {
        return job.getVersion() == null ? 0 : job.getVersion();
    }

    private String extensionOf(String storedName) {
        int dot = storedName == null ? -1 : storedName.lastIndexOf('.');
        return dot < 0 ? "" : storedName.substring(dot + 1);
    }

    /** 文書台帳登録に失敗した場合も保存実体を孤児化させない。元の例外を優先して返す。 */
    private void deleteStoredFileQuietly(StoredFile stored) {
        if (stored == null || stored.getStoredName() == null) {
            return;
        }
        try {
            fileStorageService.delete(stored.getStoredName());
        } catch (RuntimeException cleanupFailure) {
            log.error("スキルシート保存実体の補償削除に失敗しました: fileName={}",
                    stored.getStoredName(), cleanupFailure);
        }
    }
}
