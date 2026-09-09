package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import com.ses.service.accounting.AccountingTenantContextHolder;
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
    public Page<BpAvailabilityIngestion> pageForCurrentTenant(Page<BpAvailabilityIngestion> page, String status) {
        return baseMapper.selectPageForTenant(page, requireTenant(), status);
    }

    @Override
    public BpAvailabilityIngestion getForCurrentTenant(Long id) {
        return baseMapper.selectByIdForTenant(id, requireTenant());
    }

    @Override
    public BpAvailabilityIngestion createJob(MultipartFile file) {
        String tenantId = requireTenant();
        StoredFile stored = fileStorageService.store(file, FileKind.BP_EMAIL);

        BpAvailabilityIngestion job = new BpAvailabilityIngestion();
        int dotIndex = stored.getStoredName().lastIndexOf('.');
        job.setFileExt(dotIndex >= 0 ? stored.getStoredName().substring(dotIndex + 1) : "");
        job.setOriginalFileName(stored.getOriginalName());
        job.setStoredFileName(stored.getStoredName());
        job.setStatus(STATUS_PENDING);
        this.save(job);

        log.info("要員空き状況メール取込ジョブを作成しました (FILE): jobId={}", job.getId());
        selfProvider.getIfAvailable().parseAsync(job.getId(), tenantId);
        return job;
    }

    @Override
    public BpAvailabilityIngestion createJobFromPaste(String text) {
        String tenantId = requireTenant();
        BpAvailabilityIngestion job = new BpAvailabilityIngestion();
        job.setFileExt("PASTE");
        job.setExtractedText(text);
        job.setStatus(STATUS_PENDING);
        this.save(job);

        log.info("要員空き状況メール取込ジョブを作成しました (PASTE): jobId={}", job.getId());
        selfProvider.getIfAvailable().parseAsync(job.getId(), tenantId);
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
        BpAvailabilityIngestion job = baseMapper.selectByIdForTenant(id, tenantId);
        if (job == null) return;

        boolean casOk = casStatus(id, tenantId, STATUS_PENDING, STATUS_PARSING);
        if (!casOk) {
            casOk = casStatus(id, tenantId, STATUS_REVIEW, STATUS_PARSING);
            if (!casOk) {
                casOk = casStatus(id, tenantId, STATUS_FAILED, STATUS_PARSING);
            }
        }
        if (!casOk) {
            log.warn("状態遷移ができませんでした: id={}", id);
            return;
        }

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

            baseMapper.updateParsedForTenant(id, tenantId, STATUS_REVIEW, text, parsedJson,
                    aiConfig.getProvider(), aiConfig.getModel());

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
        selfProvider.getIfAvailable().parseAsync(id, requireTenant());
    }

    @Override
    public void saveReview(Long id, ReviewedBpAvailabilityDto dto) {
        BpAvailabilityIngestion job = getJobOrThrow(id);
        if (!STATUS_REVIEW.equals(job.getStatus())) {
            throw BusinessException.of("error.projectIngestion.invalidStatus");
        }
        try {
            String parsedJson = objectMapper.writeValueAsString(dto);
            baseMapper.updateReviewForTenant(id, requireTenant(), parsedJson, dto.getReviewNote());
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
        if (dto.getBpCompany() != null && !dto.getBpCompany().isBlank() && dto.getBpCompanyId() == null) {
            throw BusinessException.of(400, "error.bpAvailability.bpCompanyRequired");
        }

        BpAvailability availability = new BpAvailability();
        availability.setInitialName(dto.getInitialName());
        availability.setBpCompany(dto.getBpCompany());
        availability.setBpCompanyId(dto.getBpCompanyId());
        
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

        int updated = baseMapper.confirmForTenant(id, requireTenant(), availabilityId, dto.getReviewNote());
        if (updated == 0) {
            throw BusinessException.of(409, "error.projectIngestion.alreadyConfirmed");
        }

        return availabilityId;
    }

    @Override
    public void reject(Long id, String reason) {
        getJobOrThrow(id);
        int updated = baseMapper.rejectForTenant(id, requireTenant(), reason);
        if (updated == 0) {
            throw BusinessException.of(409, "error.projectIngestion.invalidStatus");
        }
    }

    private BpAvailabilityIngestion getJobOrThrow(Long id) {
        BpAvailabilityIngestion job = baseMapper.selectByIdForTenant(id, requireTenant());
        if (job == null) {
            throw BusinessException.of(404, "error.projectIngestion.notFound");
        }
        return job;
    }

    private String requireTenant() {
        return AccountingTenantContextHolder.requireTenantContext();
    }

    private boolean casStatus(Long id, String tenantId, String fromStatus, String toStatus) {
        int count = baseMapper.updateStatusForTenant(id, tenantId, fromStatus, toStatus);
        return count > 0;
    }

    private void updateFailed(Long id, String message) {
        String tenantId = requireTenant();
        baseMapper.updateFailedForTenant(id, tenantId, message != null && message.length() > 500
                ? message.substring(0, 500) : message);
    }
}
