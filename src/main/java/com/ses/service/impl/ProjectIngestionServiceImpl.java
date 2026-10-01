package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.enums.FileKind;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.EntityProtectUtil;
import com.ses.config.AiConfig;
import com.ses.dto.file.StoredFile;
import com.ses.dto.projectingestion.ParsedProjectDto;
import com.ses.dto.projectingestion.ReviewedProjectDto;
import com.ses.entity.Customer;
import com.ses.entity.Project;
import com.ses.entity.ProjectIngestion;
import com.ses.entity.ProjectSkill;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.ProjectIngestionMapper;
import com.ses.service.DocumentTextExtractor;
import com.ses.service.FileStorageService;
import com.ses.service.ProjectIngestionService;
import com.ses.service.ProjectService;
import com.ses.service.ProjectSkillService;
import com.ses.service.SkillTagResolver;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.ai.ProjectParseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/**
 * 案件メール取込サービス実装。
 *
 * <p>ジョブの全状態遷移は tenant と version を含む専用SQLだけを使用する。
 * 非同期解析にもtenantを明示的に渡し、worker threadへ認証threadの状態を継承させない。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectIngestionServiceImpl
        extends ServiceImpl<ProjectIngestionMapper, ProjectIngestion>
        implements ProjectIngestionService {

    private static final String STATUS_PENDING = "取込待ち";
    private static final String STATUS_PARSING = "抽出中";
    private static final String STATUS_REVIEW = "要確認";
    private static final String STATUS_DONE = "確定済";
    private static final String STATUS_REJECTED = "却下";
    private static final String STATUS_FAILED = "失敗";

    private final FileStorageService fileStorageService;
    private final DocumentTextExtractor documentTextExtractor;
    private final ProjectParseService projectParseService;
    private final ProjectService projectService;
    private final ProjectSkillService projectSkillService;
    private final SkillTagResolver skillTagResolver;
    private final AiConfig aiConfig;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<ProjectIngestionService> selfProvider;
    private final CustomerMapper customerMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.service.security.LegalEntityContextService legalEntityContextService;

    /** 継承した汎用saveも、作成元の法人を必ず現在のsecurity contextへ束縛する。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean save(ProjectIngestion entity) {
        if (entity == null || legalEntityContextService == null) {
            throw BusinessException.of(403, "LEGAL_ENTITY_CONTEXT_REQUIRED");
        }
        Long currentLegalEntityId = legalEntityContextService.requireCurrentLegalEntityId();
        if (entity.getLegalEntityId() != null) {
            legalEntityContextService.assertSame(currentLegalEntityId, entity.getLegalEntityId());
        }
        entity.setLegalEntityId(currentLegalEntityId);
        return super.save(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateById(ProjectIngestion entity) {
        if (entity == null || entity.getId() == null || legalEntityContextService == null) {
            throw BusinessException.of(403, "LEGAL_ENTITY_CONTEXT_REQUIRED");
        }
        ProjectIngestion current = super.getById(entity.getId());
        assertJobLegalEntity(current);
        if (entity.getLegalEntityId() != null) {
            legalEntityContextService.assertSame(current.getLegalEntityId(), entity.getLegalEntityId());
        }
        entity.setLegalEntityId(current.getLegalEntityId());
        return super.updateById(entity);
    }

    @Override
    public ProjectIngestion createJob(MultipartFile file) {
        requireLegalEntityContext();
        String tenantId = requireTenant();
        StoredFile stored = fileStorageService.store(file, FileKind.PROJECT_EMAIL);
        ProjectIngestion job = new ProjectIngestion();
        job.setTenantId(tenantId);
        job.setSourceType("EML");
        job.setOriginalFileName(stored.getOriginalName());
        job.setStoredFileName(stored.getStoredName());
        job.setStatus(STATUS_PENDING);
        job.setLegalEntityId(legalEntityContextService.requireCurrentLegalEntityId());
        job.setVersion(0);
        if (baseMapper.insert(job) != 1) throw BusinessException.of("error.systemError");
        log.info("案件メール取込ジョブを作成しました (FILE): jobId={}", job.getId());
        ProjectIngestionService self = selfProvider.getIfAvailable();
        if (self != null) self.parseAsync(job.getId(), tenantId);
        return job;
    }

    @Override
    public ProjectIngestion createJobFromPaste(String text) {
        requireLegalEntityContext();
        String tenantId = requireTenant();
        ProjectIngestion job = new ProjectIngestion();
        job.setTenantId(tenantId);
        job.setSourceType("PASTE");
        job.setRawText(text);
        job.setStatus(STATUS_PENDING);
        job.setLegalEntityId(legalEntityContextService.requireCurrentLegalEntityId());
        job.setVersion(0);
        if (baseMapper.insert(job) != 1) throw BusinessException.of("error.systemError");
        log.info("案件メール取込ジョブを作成しました (PASTE): jobId={}", job.getId());
        ProjectIngestionService self = selfProvider.getIfAvailable();
        if (self != null) self.parseAsync(job.getId(), tenantId);
        return job;
    }

    @Override
    public Page<ProjectIngestion> pageForCurrentTenant(Page<ProjectIngestion> page, String status) {
        return baseMapper.selectPageForTenant(page, requireTenant(), status);
    }

    @Override
    public ProjectIngestion getForCurrentTenant(Long id) {
        return baseMapper.selectByIdForTenant(id, requireTenant());
    }

    @Override
    @Async("taskExecutor")
    public void parseAsync(Long id, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw BusinessException.of(403, "error.tenant.contextRequired");
        }
        AccountingTenantContextHolder.runWithTenant(tenantId, () -> parseInTenant(id, tenantId));
    }

    private void parseInTenant(Long id, String tenantId) {
        ProjectIngestion job = baseMapper.selectByIdForTenant(id, tenantId);
        if (job == null) return;
        Integer expectedVersion = versionOf(job);
        if (baseMapper.casStatusForTenant(id, tenantId, job.getStatus(), STATUS_PARSING,
                expectedVersion) != 1) {
            log.warn("案件メール取込の状態遷移が競合しました: id={}, tenantId={}", id, tenantId);
            return;
        }
        ProjectIngestion parsingJob = baseMapper.selectByIdForTenant(id, tenantId);
        if (parsingJob == null) return;
        assertJobLegalEntity(parsingJob);
        Integer parsingVersion = versionOf(parsingJob);
        try {
            String text = parsingJob.getRawText();
            if ("EML".equals(parsingJob.getSourceType())) {
                String storedName = parsingJob.getStoredFileName();
                String ext = storedName != null && storedName.contains(".")
                        ? storedName.substring(storedName.lastIndexOf('.') + 1) : "";
                text = documentTextExtractor.extract(storedName, ext);
            }
            if (text == null || text.isBlank()) {
                updateFailed(id, tenantId, parsingVersion, "テキスト抽出に失敗しました。");
                return;
            }
            ParsedProjectDto parsed = projectParseService.parse(text);
            String parsedJson = objectMapper.writeValueAsString(parsed);
            if (baseMapper.saveParsedForTenant(id, tenantId, text, parsedJson,
                    aiConfig.getProvider(), aiConfig.getModel(), parsingVersion) != 1) {
                log.warn("案件メール解析結果の保存が競合しました: id={}, tenantId={}", id, tenantId);
            }
        } catch (BusinessException e) {
            updateFailed(id, tenantId, parsingVersion, e.getMessage());
        } catch (Exception e) {
            log.error("案件メール解析失敗（予期しないエラー）: jobId={}", id, e);
            updateFailed(id, tenantId, parsingVersion, "内部エラーが発生しました。");
        }
    }

    @Override
    public void reparse(Long id) {
        String tenantId = requireTenant();
        ProjectIngestion job = getJobOrThrow(id, tenantId);
        assertJobLegalEntity(job);
        if (!STATUS_REVIEW.equals(job.getStatus()) && !STATUS_FAILED.equals(job.getStatus())) {
            throw BusinessException.of("error.projectIngestion.invalidStatus");
        }
        ProjectIngestionService self = selfProvider.getIfAvailable();
        if (self != null) self.parseAsync(id, tenantId);
    }

    @Override
    public void saveReview(Long id, ReviewedProjectDto dto) {
        String tenantId = requireTenant();
        ProjectIngestion job = getJobOrThrow(id, tenantId);
        assertJobLegalEntity(job);
        if (!STATUS_REVIEW.equals(job.getStatus())) {
            throw BusinessException.of("error.projectIngestion.invalidStatus");
        }
        try {
            String parsedJson = objectMapper.writeValueAsString(dto);
            if (baseMapper.saveReviewForTenant(id, tenantId, parsedJson, dto.getReviewNote(),
                    versionOf(job)) != 1) {
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
    public Long confirm(Long id, ReviewedProjectDto dto) {
        String tenantId = requireTenant();
        ProjectIngestion job = getJobOrThrowForUpdate(id, tenantId);
        assertJobLegalEntity(job);
        if (job.getConvertedProjectId() != null) {
            throw BusinessException.of(409, "error.projectIngestion.alreadyConfirmed");
        }
        if (!STATUS_REVIEW.equals(job.getStatus())) {
            throw BusinessException.of("error.projectIngestion.invalidStatus");
        }
        ReviewedProjectDto.ProjectPart pp = dto.getProject();
        if (pp == null || pp.getName() == null || pp.getName().isBlank()) {
            throw BusinessException.of("error.project.nameRequired");
        }
        if (pp.getCustomerId() == null) {
            throw BusinessException.of(400, "error.project.customerRequired");
        }
        Customer customer = customerMapper.selectByIdForTenant(pp.getCustomerId(), tenantId);
        if (customer == null) throw BusinessException.of(404, "error.scope.notFound");

        Project project = new Project();
        project.setProjectName(pp.getName());
        project.setCustomerId(customer.getId());
        project.setUnitPriceMin(pp.getMinUnitPrice());
        project.setUnitPriceMax(pp.getMaxUnitPrice());
        project.setWorkLocation(pp.getLocation());
        project.setRemoteType(pp.getRemoteAllowed());
        project.setStartDate(pp.getStartDate());
        project.setEndDate(pp.getEndDate());
        project.setCommercialFlow(pp.getCommercialFlow());
        project.setRequiredCount(pp.getHeadCount() != null ? pp.getHeadCount() : 1);
        project.setDescription(pp.getDescription());
        if (pp.getEndClientName() != null && !pp.getEndClientName().isBlank()) {
            project.setRemarks("エンド顧客名: " + pp.getEndClientName());
        }
        project.setStatus("募集中");
        EntityProtectUtil.protectForCreate(project);
        if (!projectService.save(project) || project.getId() == null) {
            throw BusinessException.of("error.systemError");
        }

        if (dto.getSkills() != null && !dto.getSkills().isEmpty()) {
            List<ProjectSkill> skillEntities = new ArrayList<>();
            for (ReviewedProjectDto.SkillPart sp : dto.getSkills()) {
                if (sp.getName() == null || sp.getName().isBlank()) continue;
                try {
                    Long skillId = skillTagResolver.resolveOrCreate(sp.getName());
                    ProjectSkill skill = new ProjectSkill();
                    skill.setProjectId(project.getId());
                    skill.setSkillId(skillId);
                    skillEntities.add(skill);
                } catch (Exception e) {
                    log.warn("案件スキル登録をスキップしました: skillName={}", sp.getName());
                }
            }
            if (!skillEntities.isEmpty()) {
                com.ses.dto.skill.SkillReplaceRequest request = new com.ses.dto.skill.SkillReplaceRequest();
                request.setExpectedVersion(0);
                request.setReason("案件メール取込確定");
                request.setSkills(skillEntities.stream().map(skill -> {
                    com.ses.dto.skill.SkillReplaceRequest.SkillItem item =
                            new com.ses.dto.skill.SkillReplaceRequest.SkillItem();
                    item.setSkillId(skill.getSkillId());
                    item.setRequiredLevel(skill.getRequiredLevel());
                    item.setIsMust(skill.getIsMust());
                    return item;
                }).toList());
                projectSkillService.replaceSkills(project.getId(), request);
            }
        }

        if (baseMapper.confirmForTenant(id, tenantId, project.getId(), dto.getReviewNote(),
                versionOf(job)) != 1) {
            throw BusinessException.of(409, "error.projectIngestion.alreadyConfirmed");
        }
        return project.getId();
    }

    @Override
    public void reject(Long id, String reason) {
        String tenantId = requireTenant();
        ProjectIngestion job = getJobOrThrowForUpdate(id, tenantId);
        assertJobLegalEntity(job);
        if (baseMapper.rejectForTenant(id, tenantId, reason, versionOf(job)) != 1) {
            throw BusinessException.of(409, "error.projectIngestion.invalidStatus");
        }
    }

    private ProjectIngestion getJobOrThrow(Long id, String tenantId) {
        ProjectIngestion job = baseMapper.selectByIdForTenant(id, tenantId);
        if (job == null) throw BusinessException.of(404, "error.projectIngestion.notFound");
        return job;
    }

    private void requireLegalEntityContext() {
        if (legalEntityContextService == null) {
            throw BusinessException.of(503, "LEGAL_ENTITY_CONTEXT_REQUIRED");
        }
    }

    private void assertJobLegalEntity(ProjectIngestion job) {
        requireLegalEntityContext();
        if (job == null || job.getLegalEntityId() == null) {
            throw BusinessException.of(403, "LEGAL_ENTITY_CONTEXT_REQUIRED");
        }
        legalEntityContextService.assertCurrent(job.getLegalEntityId());
    }

    private ProjectIngestion getJobOrThrowForUpdate(Long id, String tenantId) {
        ProjectIngestion job = baseMapper.selectByIdForUpdateForTenant(id, tenantId);
        if (job == null) throw BusinessException.of(404, "error.projectIngestion.notFound");
        return job;
    }

    private void updateFailed(Long id, String tenantId, Integer expectedVersion, String message) {
        String safeMessage = message != null && message.length() > 500 ? message.substring(0, 500) : message;
        baseMapper.updateFailedForTenant(id, tenantId, safeMessage, expectedVersion);
    }

    private Integer versionOf(ProjectIngestion job) {
        return job.getVersion() == null ? 0 : job.getVersion();
    }

    private String requireTenant() {
        return AccountingTenantContextHolder.requireTenantContext();
    }
}
