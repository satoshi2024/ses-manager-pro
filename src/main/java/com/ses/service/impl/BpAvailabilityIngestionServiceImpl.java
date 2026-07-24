package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import com.ses.common.enums.FileKind;
import com.ses.dto.file.StoredFile;
import com.ses.dto.bpavailability.ParsedBpAvailabilityDto;
import com.ses.dto.bpavailability.ReviewedBpAvailabilityDto;
import com.ses.entity.BpAvailability;
import com.ses.entity.BpAvailabilityIngestion;
import com.ses.mapper.BpAvailabilityIngestionMapper;
import com.ses.service.DocumentTextExtractor;
import com.ses.service.FileStorageService;
import com.ses.service.BpAvailabilityIngestionService;
import com.ses.service.BpAvailabilityService;
import com.ses.service.skillsheet.BpAvailabilityParseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

@Slf4j
@Service
@RequiredArgsConstructor
public class BpAvailabilityIngestionServiceImpl
        extends ServiceImpl<BpAvailabilityIngestionMapper, BpAvailabilityIngestion>
        implements BpAvailabilityIngestionService {

    private static final String STATUS_PENDING  = "取込待ち";
    private static final String STATUS_PARSING  = "抽出中";
    private static final String STATUS_REVIEW   = "要確認";
    private static final String STATUS_DONE     = "確定済";
    private static final String STATUS_REJECTED = "却下";
    private static final String STATUS_FAILED   = "失敗";

    private final FileStorageService fileStorageService;
    private final DocumentTextExtractor documentTextExtractor;
    private final BpAvailabilityParseService parseService;
    private final BpAvailabilityService bpAvailabilityService;
    private final AiConfig aiConfig;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<BpAvailabilityIngestionService> selfProvider;

    @Override
    public BpAvailabilityIngestion createJob(MultipartFile file) {
        StoredFile stored = fileStorageService.store(file, FileKind.BP_EMAIL);

        BpAvailabilityIngestion job = new BpAvailabilityIngestion();
        int dotIndex = stored.getStoredName().lastIndexOf('.');
        job.setFileExt(dotIndex >= 0 ? stored.getStoredName().substring(dotIndex + 1) : "");
        job.setOriginalFileName(stored.getOriginalName());
        job.setStoredFileName(stored.getStoredName());
        job.setStatus(STATUS_PENDING);
        this.save(job);

        log.info("要員空き状況メール取込ジョブを作成しました (FILE): jobId={}", job.getId());
        selfProvider.getIfAvailable().parseAsync(job.getId());
        return job;
    }

    @Override
    public BpAvailabilityIngestion createJobFromPaste(String text) {
        BpAvailabilityIngestion job = new BpAvailabilityIngestion();
        job.setFileExt("PASTE");
        job.setExtractedText(text);
        job.setStatus(STATUS_PENDING);
        this.save(job);

        log.info("要員空き状況メール取込ジョブを作成しました (PASTE): jobId={}", job.getId());
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

        BpAvailabilityIngestion job = this.getById(id);
        if (job == null) return;

        try {
            String text = job.getExtractedText();
            if (!"PASTE".equals(job.getFileExt())) {
                text = documentTextExtractor.extract(job.getStoredFileName(), job.getFileExt());
                job.setExtractedText(text);
            }

            if (text == null || text.isBlank()) {
                updateFailed(id, "テキスト抽出に失敗しました。");
                return;
            }

            ParsedBpAvailabilityDto parsed = parseService.parse(text);
            String parsedJson = objectMapper.writeValueAsString(parsed);

            LambdaUpdateWrapper<BpAvailabilityIngestion> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(BpAvailabilityIngestion::getId, id)
                   .eq(BpAvailabilityIngestion::getStatus, STATUS_PARSING)
                   .set(BpAvailabilityIngestion::getStatus, STATUS_REVIEW)
                   .set(BpAvailabilityIngestion::getExtractedText, text)
                   .set(BpAvailabilityIngestion::getParsedJson, parsedJson)
                   .set(BpAvailabilityIngestion::getAiProvider, aiConfig.getProvider())
                   .set(BpAvailabilityIngestion::getAiModel, aiConfig.getModel());
            this.update(wrapper);

        } catch (BusinessException e) {
            updateFailed(id, e.getMessage());
        } catch (Exception e) {
            log.error("要員空き状況メール解析失敗（予期しないエラー）: jobId={}", id, e);
            updateFailed(id, "内部エラーが発生しました。");
        }
    }

    @Override
    public void reparse(Long id) {
        BpAvailabilityIngestion job = getJobOrThrow(id);
        String status = job.getStatus();
        if (!STATUS_REVIEW.equals(status) && !STATUS_FAILED.equals(status)) {
            throw BusinessException.of("error.projectIngestion.invalidStatus");
        }
        selfProvider.getIfAvailable().parseAsync(id);
    }

    @Override
    public void saveReview(Long id, ReviewedBpAvailabilityDto dto) {
        BpAvailabilityIngestion job = getJobOrThrow(id);
        if (!STATUS_REVIEW.equals(job.getStatus())) {
            throw BusinessException.of("error.projectIngestion.invalidStatus");
        }
        try {
            String parsedJson = objectMapper.writeValueAsString(dto);
            LambdaUpdateWrapper<BpAvailabilityIngestion> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(BpAvailabilityIngestion::getId, id)
                   .set(BpAvailabilityIngestion::getParsedJson, parsedJson)
                   .set(BpAvailabilityIngestion::getReviewNote, dto.getReviewNote());
            this.update(wrapper);
        } catch (Exception e) {
            log.error("レビュー保存に失敗しました: jobId={}", id, e);
            throw BusinessException.of("error.systemError");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long confirm(Long id, ReviewedBpAvailabilityDto dto) {
        BpAvailabilityIngestion job = getJobOrThrow(id);
        if (job.getConvertedAvailabilityId() != null) {
            throw BusinessException.of(409, "error.projectIngestion.alreadyConfirmed");
        }
        if (!STATUS_REVIEW.equals(job.getStatus())) {
            throw BusinessException.of("error.projectIngestion.invalidStatus");
        }

        if (dto == null || dto.getInitialName() == null || dto.getInitialName().isBlank()) {
            throw BusinessException.of("error.bpAvailability.nameRequired");
        }

        BpAvailability availability = new BpAvailability();
        availability.setInitialName(dto.getInitialName());
        availability.setBpCompany(dto.getBpCompany());
        
        try {
            availability.setSkillsJson(objectMapper.writeValueAsString(dto.getSkills()));
        } catch (Exception e) {
            availability.setSkillsJson("[]");
        }

        availability.setUnitPrice(dto.getUnitPrice());
        if (dto.getAvailableFrom() != null && !dto.getAvailableFrom().isBlank()) {
            try {
                availability.setAvailableFrom(LocalDate.parse(dto.getAvailableFrom()));
            } catch (Exception e) {
                // Ignore parse error
            }
        }
        availability.setExperienceYears(dto.getExperienceYears());
        availability.setStatus("提案可能");
        availability.setRemarks(dto.getRemarks());

        com.ses.common.util.EntityProtectUtil.protectForCreate(availability);
        bpAvailabilityService.save(availability);
        Long availabilityId = availability.getId();

        int updated = baseMapper.update(null, new LambdaUpdateWrapper<BpAvailabilityIngestion>()
                .eq(BpAvailabilityIngestion::getId, id)
                .eq(BpAvailabilityIngestion::getStatus, STATUS_REVIEW)
                .isNull(BpAvailabilityIngestion::getConvertedAvailabilityId)
                .set(BpAvailabilityIngestion::getStatus, STATUS_DONE)
                .set(BpAvailabilityIngestion::getConvertedAvailabilityId, availabilityId)
                .set(BpAvailabilityIngestion::getReviewNote, dto.getReviewNote()));
        if (updated == 0) {
            throw BusinessException.of(409, "error.projectIngestion.alreadyConfirmed");
        }

        return availabilityId;
    }

    @Override
    public void reject(Long id, String reason) {
        getJobOrThrow(id);
        int updated = baseMapper.update(null, new LambdaUpdateWrapper<BpAvailabilityIngestion>()
                .eq(BpAvailabilityIngestion::getId, id)
                .in(BpAvailabilityIngestion::getStatus, STATUS_PENDING, STATUS_PARSING, STATUS_REVIEW, STATUS_FAILED)
                .set(BpAvailabilityIngestion::getStatus, STATUS_REJECTED)
                .set(BpAvailabilityIngestion::getErrorMessage, reason));
        if (updated == 0) {
            throw BusinessException.of(409, "error.projectIngestion.invalidStatus");
        }
    }

    private BpAvailabilityIngestion getJobOrThrow(Long id) {
        BpAvailabilityIngestion job = this.getById(id);
        if (job == null) {
            throw BusinessException.of(404, "error.projectIngestion.notFound");
        }
        return job;
    }

    private boolean casStatus(Long id, String fromStatus, String toStatus) {
        int count = baseMapper.update(null, new LambdaUpdateWrapper<BpAvailabilityIngestion>()
                .eq(BpAvailabilityIngestion::getId, id)
                .eq(BpAvailabilityIngestion::getStatus, fromStatus)
                .set(BpAvailabilityIngestion::getStatus, toStatus));
        return count > 0;
    }

    private void updateFailed(Long id, String message) {
        baseMapper.update(null, new LambdaUpdateWrapper<BpAvailabilityIngestion>()
                .eq(BpAvailabilityIngestion::getId, id)
                .set(BpAvailabilityIngestion::getStatus, STATUS_FAILED)
                .set(BpAvailabilityIngestion::getErrorMessage, message != null && message.length() > 500
                        ? message.substring(0, 500) : message));
    }
}
