package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import com.ses.common.enums.FileKind;
import com.ses.dto.file.StoredFile;
import com.ses.dto.projectingestion.ParsedProjectDto;
import com.ses.dto.projectingestion.ReviewedProjectDto;
import com.ses.entity.Project;
import com.ses.entity.ProjectIngestion;
import com.ses.entity.ProjectSkill;
import com.ses.mapper.ProjectIngestionMapper;
import com.ses.service.DocumentTextExtractor;
import com.ses.service.FileStorageService;
import com.ses.service.ProjectIngestionService;
import com.ses.service.ProjectService;
import com.ses.service.ProjectSkillService;
import com.ses.service.SkillTagResolver;
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
import java.util.UUID;

/**
 * 案件メール取込サービス実装。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectIngestionServiceImpl
        extends ServiceImpl<ProjectIngestionMapper, ProjectIngestion>
        implements ProjectIngestionService {

    private static final String STATUS_PENDING  = "取込待ち";
    private static final String STATUS_PARSING  = "抽出中";
    private static final String STATUS_REVIEW   = "要確認";
    private static final String STATUS_DONE     = "確定済";
    private static final String STATUS_REJECTED = "却下";
    private static final String STATUS_FAILED   = "失敗";

    private final FileStorageService fileStorageService;
    private final DocumentTextExtractor documentTextExtractor;
    private final ProjectParseService projectParseService;
    private final ProjectService projectService;
    private final ProjectSkillService projectSkillService;
    private final SkillTagResolver skillTagResolver;
    private final AiConfig aiConfig;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<ProjectIngestionService> selfProvider;

    @Override
    public ProjectIngestion createJob(MultipartFile file) {
        StoredFile stored = fileStorageService.store(file, FileKind.PROJECT_EMAIL);

        ProjectIngestion job = new ProjectIngestion();
        job.setSourceType("EML");
        job.setOriginalFileName(stored.getOriginalName());
        job.setStoredFileName(stored.getStoredName());
        job.setStatus(STATUS_PENDING);
        this.save(job);

        log.info("案件メール取込ジョブを作成しました (FILE): jobId={}", job.getId());
        selfProvider.getIfAvailable().parseAsync(job.getId());
        return job;
    }

    @Override
    public ProjectIngestion createJobFromPaste(String text) {
        ProjectIngestion job = new ProjectIngestion();
        job.setSourceType("PASTE");
        job.setRawText(text);
        job.setStatus(STATUS_PENDING);
        this.save(job);

        log.info("案件メール取込ジョブを作成しました (PASTE): jobId={}", job.getId());
        selfProvider.getIfAvailable().parseAsync(job.getId());
        return job;
    }

    @Override
    @Async("taskExecutor")
    public void parseAsync(Long id) {
        boolean casOk = casStatus(id, STATUS_PENDING, STATUS_PARSING);
        if (!casOk) {
            casOk = casStatus(id, STATUS_REVIEW, STATUS_PARSING);
            if (!casOk) {
                casOk = casStatus(id, STATUS_FAILED, STATUS_PARSING);
            }
        }
        if (!casOk) {
            log.warn("状態遷移ができませんでした: id={}", id);
            return;
        }

        ProjectIngestion job = this.getById(id);
        if (job == null) return;

        try {
            String text = job.getRawText();
            if ("EML".equals(job.getSourceType())) {
                String ext = job.getStoredFileName().contains(".")
                        ? job.getStoredFileName().substring(job.getStoredFileName().lastIndexOf('.') + 1)
                        : "";
                text = documentTextExtractor.extract(job.getStoredFileName(), ext);
                job.setRawText(text);
            }

            if (text == null || text.isBlank()) {
                updateFailed(id, "テキスト抽出に失敗しました。");
                return;
            }

            ParsedProjectDto parsed = projectParseService.parse(text);
            String parsedJson = objectMapper.writeValueAsString(parsed);

            LambdaUpdateWrapper<ProjectIngestion> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(ProjectIngestion::getId, id)
                   .eq(ProjectIngestion::getStatus, STATUS_PARSING)
                   .set(ProjectIngestion::getStatus, STATUS_REVIEW)
                   .set(ProjectIngestion::getRawText, text)
                   .set(ProjectIngestion::getParsedJson, parsedJson)
                   .set(ProjectIngestion::getAiProvider, aiConfig.getProvider())
                   .set(ProjectIngestion::getAiModel, aiConfig.getModel());
            this.update(wrapper);

        } catch (BusinessException e) {
            updateFailed(id, e.getMessage());
        } catch (Exception e) {
            log.error("案件メール解析失敗（予期しないエラー）: jobId={}", id, e);
            updateFailed(id, "内部エラーが発生しました。");
        }
    }

    @Override
    public void reparse(Long id) {
        ProjectIngestion job = getJobOrThrow(id);
        String status = job.getStatus();
        if (!STATUS_REVIEW.equals(status) && !STATUS_FAILED.equals(status)) {
            throw BusinessException.of("error.projectIngestion.invalidStatus");
        }
        selfProvider.getIfAvailable().parseAsync(id);
    }

    @Override
    public void saveReview(Long id, ReviewedProjectDto dto) {
        ProjectIngestion job = getJobOrThrow(id);
        if (!STATUS_REVIEW.equals(job.getStatus())) {
            throw BusinessException.of("error.projectIngestion.invalidStatus");
        }
        try {
            String parsedJson = objectMapper.writeValueAsString(dto);
            LambdaUpdateWrapper<ProjectIngestion> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(ProjectIngestion::getId, id)
                   .set(ProjectIngestion::getParsedJson, parsedJson)
                   .set(ProjectIngestion::getReviewNote, dto.getReviewNote());
            this.update(wrapper);
        } catch (Exception e) {
            log.error("レビュー保存に失敗しました: jobId={}", id, e);
            throw BusinessException.of("error.systemError");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long confirm(Long id, ReviewedProjectDto dto) {
        ProjectIngestion job = getJobOrThrow(id);
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

        // Projectエンティティ生成
        Project project = new Project();
        project.setProjectName(pp.getName());
        project.setUnitPriceMin(pp.getMinUnitPrice());
        project.setUnitPriceMax(pp.getMaxUnitPrice());
        project.setWorkLocation(pp.getLocation());
        project.setRemoteType(pp.getRemoteAllowed());
        project.setStartDate(pp.getStartDate());
        project.setEndDate(pp.getEndDate());
        project.setCommercialFlow(pp.getCommercialFlow());
        project.setRequiredCount(pp.getHeadCount() != null ? pp.getHeadCount() : 1);
        project.setDescription(pp.getDescription());
        // エンド顧客名は一旦備考へ退避するか、Projectの顧客ID管理などに応じる（ここではRemarksへ）
        if (pp.getEndClientName() != null && !pp.getEndClientName().isBlank()) {
            project.setRemarks("エンド顧客名: " + pp.getEndClientName());
        }
        project.setStatus("募集中"); // StatusConstantsに合わせる（既存要件）

        com.ses.common.util.EntityProtectUtil.protectForCreate(project);
        projectService.save(project);
        Long projectId = project.getId();

        // 案件スキル登録
        if (dto.getSkills() != null && !dto.getSkills().isEmpty()) {
            List<ProjectSkill> skillEntities = new ArrayList<>();
            for (ReviewedProjectDto.SkillPart sp : dto.getSkills()) {
                if (sp.getName() == null || sp.getName().isBlank()) continue;
                try {
                    Long skillId = skillTagResolver.resolveOrCreate(sp.getName());
                    ProjectSkill ps = new ProjectSkill();
                    ps.setProjectId(projectId);
                    ps.setSkillId(skillId);
                    skillEntities.add(ps);
                } catch (Exception e) {
                    log.warn("案件スキル登録をスキップしました: skillName={}", sp.getName());
                }
            }
            if (!skillEntities.isEmpty()) {
                projectSkillService.replaceSkills(projectId, skillEntities);
            }
        }

        int updated = baseMapper.update(null, new LambdaUpdateWrapper<ProjectIngestion>()
                .eq(ProjectIngestion::getId, id)
                .eq(ProjectIngestion::getStatus, STATUS_REVIEW)
                .isNull(ProjectIngestion::getConvertedProjectId)
                .set(ProjectIngestion::getStatus, STATUS_DONE)
                .set(ProjectIngestion::getConvertedProjectId, projectId)
                .set(ProjectIngestion::getReviewNote, dto.getReviewNote()));
        if (updated == 0) {
            throw BusinessException.of(409, "error.projectIngestion.alreadyConfirmed");
        }

        return projectId;
    }

    @Override
    public void reject(Long id, String reason) {
        getJobOrThrow(id);
        int updated = baseMapper.update(null, new LambdaUpdateWrapper<ProjectIngestion>()
                .eq(ProjectIngestion::getId, id)
                .in(ProjectIngestion::getStatus, STATUS_PENDING, STATUS_PARSING, STATUS_REVIEW, STATUS_FAILED)
                .set(ProjectIngestion::getStatus, STATUS_REJECTED)
                .set(ProjectIngestion::getErrorMessage, reason));
        if (updated == 0) {
            throw BusinessException.of(409, "error.projectIngestion.invalidStatus");
        }
    }

    private ProjectIngestion getJobOrThrow(Long id) {
        ProjectIngestion job = this.getById(id);
        if (job == null) {
            throw BusinessException.of(404, "error.projectIngestion.notFound");
        }
        return job;
    }

    private boolean casStatus(Long id, String fromStatus, String toStatus) {
        int count = baseMapper.update(null, new LambdaUpdateWrapper<ProjectIngestion>()
                .eq(ProjectIngestion::getId, id)
                .eq(ProjectIngestion::getStatus, fromStatus)
                .set(ProjectIngestion::getStatus, toStatus));
        return count > 0;
    }

    private void updateFailed(Long id, String message) {
        baseMapper.update(null, new LambdaUpdateWrapper<ProjectIngestion>()
                .eq(ProjectIngestion::getId, id)
                .set(ProjectIngestion::getStatus, STATUS_FAILED)
                .set(ProjectIngestion::getErrorMessage, message != null && message.length() > 500
                        ? message.substring(0, 500) : message));
    }
}
