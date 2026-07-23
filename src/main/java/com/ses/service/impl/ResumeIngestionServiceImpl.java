package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import com.ses.common.enums.FileKind;
import com.ses.dto.file.StoredFile;
import com.ses.dto.resume.ParsedResumeDto;
import com.ses.dto.resume.ReviewedResumeDto;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerCareer;
import com.ses.entity.EngineerSkill;
import com.ses.entity.ResumeIngestion;
import com.ses.mapper.ResumeIngestionMapper;
import com.ses.service.CandidateService;
import com.ses.service.EngineerCareerService;
import com.ses.service.EngineerService;
import com.ses.service.EngineerSkillService;
import com.ses.service.FileStorageService;
import com.ses.service.ResumeIngestionService;
import com.ses.service.ResumeTextExtractor;
import com.ses.service.SkillTagResolver;
import com.ses.service.ai.ResumeParseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/**
 * スキルシート取込サービス実装。
 * アップロード -> ジョブ作成 -> 非同期解析 -> レビュー -> 確定のフローを管理する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeIngestionServiceImpl
        extends ServiceImpl<ResumeIngestionMapper, ResumeIngestion>
        implements ResumeIngestionService {

    private static final String STATUS_PENDING  = "\u53d6\u8fbc\u5f85\u3061";
    private static final String STATUS_PARSING  = "\u62bd\u51fa\u4e2d";
    private static final String STATUS_REVIEW   = "\u8981\u78ba\u8a8d";
    private static final String STATUS_DONE     = "\u78ba\u5b9a\u6e08";
    private static final String STATUS_REJECTED = "\u5374\u4e0b";
    private static final String STATUS_FAILED   = "\u5931\u6557";

    private final FileStorageService fileStorageService;
    private final ResumeTextExtractor resumeTextExtractor;
    private final ResumeParseService resumeParseService;
    private final EngineerService engineerService;
    private final EngineerSkillService engineerSkillService;
    private final EngineerCareerService engineerCareerService;
    private final SkillTagResolver skillTagResolver;
    private final CandidateService candidateService;
    private final AiConfig aiConfig;
    private final ObjectMapper objectMapper;

    @Override
    public ResumeIngestion createJob(MultipartFile file, Long candidateId) {
        // ファイル保存
        StoredFile stored = fileStorageService.store(file, FileKind.SKILL_SHEET);

        // ジョブ作成
        ResumeIngestion job = new ResumeIngestion();
        job.setOriginalFileName(stored.getOriginalName());
        job.setStoredFileName(stored.getStoredName());
        String ext = stored.getStoredName().contains(".")
                ? stored.getStoredName().substring(stored.getStoredName().lastIndexOf('.') + 1)
                : "";
        job.setFileExt(ext);
        job.setStatus(STATUS_PENDING);
        job.setCandidateId(candidateId);
        this.save(job);

        log.info("スキルシート取込ジョブを作成しました: jobId={}, fileName={}", job.getId(), stored.getOriginalName());

        // 非同期解析を起動
        parseAsync(job.getId());
        return job;
    }

    @Override
    @Async("taskExecutor")
    public void parseAsync(Long id) {
        // 状態を "抽出中" に CAS 更新
        boolean casOk = casStatus(id, STATUS_PENDING, STATUS_PARSING);
        // 再解析時は "要確認"/"失敗" -> "抽出中" も許容
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

        ResumeIngestion job = this.getById(id);
        if (job == null) {
            log.error("ジョブが見つかりません: id={}", id);
            return;
        }

        try {
            // 1. テキスト抽出
            String text = resumeTextExtractor.extract(job.getStoredFileName(), job.getFileExt());
            if (text == null || text.isBlank()) {
                log.warn("テキストの抽出結果が空でした: jobId={}", id);
                updateFailed(id, "テキスト抽出に失敗しました。画像 PDF または空ファイルの可能性があります。");
                return;
            }
            job.setExtractedText(text);

            // 2. AI解析
            ParsedResumeDto parsed = resumeParseService.parse(text);
            String parsedJson = objectMapper.writeValueAsString(parsed);

            // 3. 状態を "要確認" に更新
            LambdaUpdateWrapper<ResumeIngestion> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(ResumeIngestion::getId, id)
                   .eq(ResumeIngestion::getStatus, STATUS_PARSING)
                   .set(ResumeIngestion::getStatus, STATUS_REVIEW)
                   .set(ResumeIngestion::getExtractedText, text)
                   .set(ResumeIngestion::getParsedJson, parsedJson)
                   .set(ResumeIngestion::getAiProvider, aiConfig.getProvider())
                   .set(ResumeIngestion::getAiModel, aiConfig.getModel());
            this.update(wrapper);
            log.info("スキルシート解析完了: jobId={}", id);

        } catch (BusinessException e) {
            log.error("スキルシート解析失敗: jobId={}, msg={}", id, e.getMessage());
            updateFailed(id, e.getMessage());
        } catch (Exception e) {
            log.error("スキルシート解析失敗（予期しないエラー）: jobId={}", id, e);
            updateFailed(id, "内部エラーが発生しました。");
        }
    }

    @Override
    public void reparse(Long id) {
        ResumeIngestion job = getJobOrThrow(id);
        String status = job.getStatus();
        if (!STATUS_REVIEW.equals(status) && !STATUS_FAILED.equals(status)) {
            throw BusinessException.of("error.resume.invalidStatus");
        }
        parseAsync(id);
    }

    @Override
    public void saveReview(Long id, ReviewedResumeDto dto) {
        ResumeIngestion job = getJobOrThrow(id);
        if (!STATUS_REVIEW.equals(job.getStatus())) {
            throw BusinessException.of("error.resume.invalidStatus");
        }
        try {
            String parsedJson = objectMapper.writeValueAsString(dto);
            LambdaUpdateWrapper<ResumeIngestion> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(ResumeIngestion::getId, id)
                   .set(ResumeIngestion::getParsedJson, parsedJson)
                   .set(ResumeIngestion::getReviewNote, dto.getReviewNote());
            this.update(wrapper);
        } catch (Exception e) {
            throw BusinessException.of("error.resume.invalidStatus");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long confirm(Long id, ReviewedResumeDto dto) {
        // 1. ジョブ確認 + 二重確定ガード
        ResumeIngestion job = getJobOrThrow(id);
        if (job.getConvertedEngineerId() != null) {
            throw BusinessException.of(409, "error.resume.alreadyConfirmed");
        }
        if (!STATUS_REVIEW.equals(job.getStatus())) {
            throw BusinessException.of("error.resume.invalidStatus");
        }

        ReviewedResumeDto.EngineerPart ep = dto.getEngineer();
        if (ep == null || ep.getFullName() == null || ep.getFullName().isBlank()) {
            throw BusinessException.of("error.engineer.nameRequired");
        }

        // 2. 要員生成
        Engineer engineer = new Engineer();
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
        log.info("スキルシート取込から要員を作成しました: engineerId={}, jobId={}", engineerId, id);

        // 3. スキル登録（スキル名 -> skill_id 解決）
        if (dto.getSkills() != null && !dto.getSkills().isEmpty()) {
            List<EngineerSkill> skillEntities = new ArrayList<>();
            for (ReviewedResumeDto.SkillPart sp : dto.getSkills()) {
                if (sp.getName() == null || sp.getName().isBlank()) continue;
                try {
                    Long skillId = skillTagResolver.resolveOrCreate(sp.getName());
                    EngineerSkill es = new EngineerSkill();
                    es.setEngineerId(engineerId);
                    es.setSkillId(skillId);
                    es.setProficiency(sp.getProficiency());
                    es.setExperienceYears(sp.getExperienceYears());
                    skillEntities.add(es);
                } catch (Exception e) {
                    log.warn("スキル登録をスキップしました: skillName={}, reason={}", sp.getName(), e.getMessage());
                }
            }
            if (!skillEntities.isEmpty()) {
                engineerSkillService.replaceSkills(engineerId, skillEntities);
            }
        }

        // 4. 経歴登録
        if (dto.getCareers() != null) {
            for (ReviewedResumeDto.CareerPart cp : dto.getCareers()) {
                if (cp.getPeriodTo() != null && cp.getPeriodFrom() != null
                        && cp.getPeriodTo().isBefore(cp.getPeriodFrom())) {
                    log.warn("経歴の期間が不正なためスキップします: from={}, to={}", cp.getPeriodFrom(), cp.getPeriodTo());
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

        // 5. ジョブ状態を "確定済" に CAS 更新
        int updated = baseMapper.update(null, new LambdaUpdateWrapper<ResumeIngestion>()
                .eq(ResumeIngestion::getId, id)
                .eq(ResumeIngestion::getStatus, STATUS_REVIEW)
                .isNull(ResumeIngestion::getConvertedEngineerId)
                .set(ResumeIngestion::getStatus, STATUS_DONE)
                .set(ResumeIngestion::getConvertedEngineerId, engineerId)
                .set(ResumeIngestion::getReviewNote, dto.getReviewNote()));
        if (updated == 0) {
            throw BusinessException.of(409, "error.resume.alreadyConfirmed");
        }

        // 6. 候補者連携（candidate_id がある場合）
        if (job.getCandidateId() != null) {
            try {
                candidateService.linkConvertedEngineer(job.getCandidateId(), engineerId);
            } catch (Exception e) {
                log.warn("候補者連携に失敗しました（要員作成は成功した）: candidateId={}, reason={}",
                         job.getCandidateId(), e.getMessage());
            }
        }

        return engineerId;
    }

    @Override
    public void reject(Long id, String reason) {
        getJobOrThrow(id);
        LambdaUpdateWrapper<ResumeIngestion> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(ResumeIngestion::getId, id)
               .set(ResumeIngestion::getStatus, STATUS_REJECTED)
               .set(ResumeIngestion::getErrorMessage, reason);
        this.update(wrapper);
        log.info("スキルシート取込を却下しました: id={}", id);
    }

    // --- プライベートメソッド ---

    private ResumeIngestion getJobOrThrow(Long id) {
        ResumeIngestion job = this.getById(id);
        if (job == null) {
            throw BusinessException.of(404, "error.resume.notFound");
        }
        return job;
    }

    /**
     * CAS（比較和更新）で状態を変更する。
     * 0件更新の場合は false を返す。
     */
    private boolean casStatus(Long id, String fromStatus, String toStatus) {
        int count = baseMapper.update(null, new LambdaUpdateWrapper<ResumeIngestion>()
                .eq(ResumeIngestion::getId, id)
                .eq(ResumeIngestion::getStatus, fromStatus)
                .set(ResumeIngestion::getStatus, toStatus));
        return count > 0;
    }

    private void updateFailed(Long id, String message) {
        baseMapper.update(null, new LambdaUpdateWrapper<ResumeIngestion>()
                .eq(ResumeIngestion::getId, id)
                .set(ResumeIngestion::getStatus, STATUS_FAILED)
                .set(ResumeIngestion::getErrorMessage, message != null && message.length() > 500
                        ? message.substring(0, 500) : message));
    }
}
