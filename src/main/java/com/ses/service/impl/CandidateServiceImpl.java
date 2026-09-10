package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.constant.StatusConstants;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.candidate.CandidateEngineerInitialDto;
import com.ses.entity.Candidate;
import com.ses.entity.CandidateActivity;
import com.ses.mapper.CandidateActivityMapper;
import com.ses.mapper.CandidateMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.service.CandidateService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 候補者サービス実装。
 *
 * SalesActivityと見た目が似ているが意図的にコード共有しない(design.md参照)。
 * ステージ変更はここが唯一の入口とし、t_candidate_activityの記録とt_candidate.currentStageの
 * 同期更新を1トランザクションで行う。
 */
@Service
@RequiredArgsConstructor
public class CandidateServiceImpl extends ServiceImpl<CandidateMapper, Candidate> implements CandidateService {

    private static final Set<String> ALLOWED_STAGES = Set.of(
            StatusConstants.CANDIDATE_STAGE_APPLIED,
            StatusConstants.CANDIDATE_STAGE_DOCUMENT_SCREENING,
            StatusConstants.CANDIDATE_STAGE_FIRST_INTERVIEW,
            StatusConstants.CANDIDATE_STAGE_FINAL_INTERVIEW,
            StatusConstants.CANDIDATE_STAGE_OFFER,
            StatusConstants.CANDIDATE_STAGE_OFFER_DECLINED,
            StatusConstants.CANDIDATE_STAGE_HIRED,
            StatusConstants.CANDIDATE_STAGE_REJECTED
    );

    private static final Set<String> REASON_REQUIRED_STAGES = Set.of(
            StatusConstants.CANDIDATE_STAGE_REJECTED,
            StatusConstants.CANDIDATE_STAGE_OFFER_DECLINED
    );

    private static final List<String> STAGE_SEQUENCE = List.of(
            StatusConstants.CANDIDATE_STAGE_APPLIED,
            StatusConstants.CANDIDATE_STAGE_DOCUMENT_SCREENING,
            StatusConstants.CANDIDATE_STAGE_FIRST_INTERVIEW,
            StatusConstants.CANDIDATE_STAGE_FINAL_INTERVIEW,
            StatusConstants.CANDIDATE_STAGE_OFFER,
            StatusConstants.CANDIDATE_STAGE_HIRED
    );

    private final CandidateActivityMapper candidateActivityMapper;
    private final EngineerMapper engineerMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean save(Candidate candidate) {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        if (candidate.getTenantId() != null && !tenantId.equals(candidate.getTenantId())) {
            throw BusinessException.of(403, "error.tenant.mismatch");
        }
        candidate.setTenantId(tenantId);
        if (!StringUtils.hasText(candidate.getCurrentStage())) {
            candidate.setCurrentStage(StatusConstants.CANDIDATE_STAGE_APPLIED);
        } else {
            validateStage(candidate.getCurrentStage());
        }
        return super.save(candidate);
    }

    @Override
    public Page<Candidate> pageForCurrentTenant(Page<Candidate> page, String name, String stage,
                                                String skillKeyword) {
        return baseMapper.selectPageForTenant(page,
                AccountingTenantContextHolder.requireTenantContext(), name, stage, skillKeyword);
    }

    @Override
    public List<Candidate> overdueForCurrentTenant(LocalDate today) {
        return baseMapper.selectOverdueForTenant(
                AccountingTenantContextHolder.requireTenantContext(), today);
    }

    @Override
    public Candidate getForCurrentTenant(Long candidateId) {
        return baseMapper.selectByIdForTenant(candidateId,
                AccountingTenantContextHolder.requireTenantContext());
    }

    @Override
    public boolean createForCurrentTenant(Candidate candidate) {
        return save(candidate);
    }

    @Override
    public boolean updateForCurrentTenant(Long candidateId, Candidate candidate, Integer expectedVersion) {
        if (expectedVersion == null) {
            throw BusinessException.of(400, "error.common.expectedVersionRequired");
        }
        candidate.setId(candidateId);
        candidate.setVersion(expectedVersion);
        return updateById(candidate);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteForCurrentTenant(Long candidateId, Integer expectedVersion) {
        if (expectedVersion == null) {
            throw BusinessException.of(400, "error.common.expectedVersionRequired");
        }
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        Candidate current = baseMapper.selectByIdForUpdateForTenant(candidateId, tenantId);
        if (current == null) return false;
        if (!expectedVersion.equals(current.getVersion())) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        return baseMapper.deleteByIdForTenant(candidateId, tenantId, expectedVersion) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateById(Candidate candidate) {
        if (candidate == null || candidate.getId() == null || candidate.getVersion() == null) {
            throw BusinessException.of(400, "error.common.expectedVersionRequired");
        }
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        Candidate existing = baseMapper.selectByIdForUpdateForTenant(candidate.getId(), tenantId);
        if (existing == null) return false;
        if (!candidate.getVersion().equals(existing.getVersion())) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        boolean updated = baseMapper.updateByIdForTenant(candidate, tenantId, candidate.getVersion()) == 1;
        if (updated && existing.getConvertedEngineerId() != null) {
            Long engineerId = existing.getConvertedEngineerId();
            com.ses.entity.Engineer eng = engineerMapper.selectByIdForTenant(engineerId, tenantId);
            if (eng != null) {
                boolean engChanged = false;
                if (candidate.getDesiredRate() != null && !candidate.getDesiredRate().equals(eng.getExpectedUnitPrice())) {
                    eng.setExpectedUnitPrice(candidate.getDesiredRate());
                    engChanged = true;
                }
                if (engChanged) {
                    if (eng.getVersion() == null
                            || engineerMapper.updateByIdForTenant(eng, tenantId, eng.getVersion()) != 1) {
                        throw BusinessException.of(409, "error.common.optimisticLock");
                    }
                }
            }
        }
        return updated;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStage(Long candidateId, String newStage, String reason, String remarks) {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        Candidate candidate = baseMapper.selectByIdForTenant(candidateId, tenantId);
        if (candidate == null) {
            throw BusinessException.of("error.candidate.notFound");
        }
        changeStage(candidateId, newStage, reason, remarks, candidate.getVersion());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStage(Long candidateId, String newStage, String reason, String remarks,
                            Integer expectedVersion) {
        if (expectedVersion == null) {
            throw BusinessException.of(400, "error.common.expectedVersionRequired");
        }
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        Candidate candidate = baseMapper.selectByIdForUpdateForTenant(candidateId, tenantId);
        if (candidate == null) {
            throw BusinessException.of("error.candidate.notFound");
        }
        if (!expectedVersion.equals(candidate.getVersion())) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        if (!StringUtils.hasText(newStage)) {
            throw BusinessException.of("error.candidate.stageRequired");
        }
        validateStage(newStage);
        if (REASON_REQUIRED_STAGES.contains(newStage) && !StringUtils.hasText(reason)) {
            throw BusinessException.of("error.candidate.reasonRequired");
        }

        String currentStage = candidate.getCurrentStage();
        if (!newStage.equals(currentStage)) {
            if (candidate.getConvertedEngineerId() != null && StatusConstants.CANDIDATE_STAGE_HIRED.equals(currentStage)) {
                throw BusinessException.of(400, "error.candidate.cannotChangeHiredWithEngineer");
            }
            if (!StatusConstants.CANDIDATE_STAGE_REJECTED.equals(newStage) && !StatusConstants.CANDIDATE_STAGE_OFFER_DECLINED.equals(newStage)) {
                int oldIdx = STAGE_SEQUENCE.indexOf(currentStage);
                int newIdx = STAGE_SEQUENCE.indexOf(newStage);
                if (oldIdx == -1 || newIdx == -1 || Math.abs(newIdx - oldIdx) > 1) {
                    throw BusinessException.of(400, "error.candidate.invalidStageTransition");
                }
            }
        }

        // 履歴の記録
        CandidateActivity activity = new CandidateActivity();
        activity.setCandidateId(candidateId);
        activity.setStage(newStage);
        activity.setReason(reason);
        activity.setRemarks(remarks);
        activity.setChangedAt(LocalDateTime.now());
        activity.setChangedBy(SecurityUtils.currentUserId());
        candidateActivityMapper.insert(activity);

        // currentStage(非正規化キャッシュ)の同期更新
        if (baseMapper.updateStageForTenant(candidateId, tenantId, currentStage, newStage,
                expectedVersion) != 1) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
    }

    private void validateStage(String stage) {
        if (!ALLOWED_STAGES.contains(stage)) {
            throw BusinessException.of("error.candidate.stageInvalid", stage);
        }
    }

    @Override
    public List<CandidateActivity> getActivities(Long candidateId) {
        return getActivitiesForCurrentTenant(candidateId);
    }

    @Override
    public List<CandidateActivity> getActivitiesForCurrentTenant(Long candidateId) {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        if (baseMapper.selectByIdForTenant(candidateId, tenantId) == null) {
            throw BusinessException.of(404, "error.candidate.notFound");
        }
        return candidateActivityMapper.selectByCandidateIdForTenant(candidateId, tenantId);
    }

    @Override
    public CandidateEngineerInitialDto getEngineerInitialDto(Long candidateId) {
        return getEngineerInitialDtoForCurrentTenant(candidateId);
    }

    @Override
    public CandidateEngineerInitialDto getEngineerInitialDtoForCurrentTenant(Long candidateId) {
        Candidate candidate = getForCurrentTenant(candidateId);
        if (candidate == null) {
            throw BusinessException.of("error.candidate.notFound");
        }
        if (!StatusConstants.CANDIDATE_STAGE_HIRED.equals(candidate.getCurrentStage())) {
            throw BusinessException.of("error.candidate.notHiredStage");
        }
        return new CandidateEngineerInitialDto(candidate.getId(), candidate.getName(), candidate.getSkillSummary());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void linkConvertedEngineer(Long candidateId, Long engineerId) {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        Candidate candidate = baseMapper.selectByIdForTenant(candidateId, tenantId);
        if (candidate == null) {
            throw BusinessException.of("error.candidate.notFound");
        }
        linkConvertedEngineer(candidateId, engineerId, candidate.getVersion());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void linkConvertedEngineer(Long candidateId, Long engineerId, Integer expectedVersion) {
        if (expectedVersion == null) {
            throw BusinessException.of(400, "error.common.expectedVersionRequired");
        }
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        Candidate candidate = baseMapper.selectByIdForUpdateForTenant(candidateId, tenantId);
        if (candidate == null) {
            throw BusinessException.of("error.candidate.notFound");
        }
        if (!expectedVersion.equals(candidate.getVersion())) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        if (!StatusConstants.CANDIDATE_STAGE_HIRED.equals(candidate.getCurrentStage())) {
            throw BusinessException.of("error.candidate.notHiredStage");
        }
        
        if (engineerId == null) {
            throw BusinessException.of(400, "error.candidate.invalidEngineerId");
        }
        com.ses.entity.Engineer eng = engineerMapper.selectByIdForTenant(engineerId, tenantId);
        if (eng == null) {
            throw BusinessException.of(404, "error.engineer.notFound");
        }

        if (candidate.getConvertedEngineerId() != null) {
            if (candidate.getConvertedEngineerId().equals(engineerId)) {
                return; // 冪等成功
            }
            throw BusinessException.of(409, "error.candidate.alreadyLinked");
        }
        
        // 他候補者との重複チェック
        if (baseMapper.countByConvertedEngineerForTenant(engineerId, tenantId) > 0) {
            throw BusinessException.of(409, "error.candidate.alreadyLinked");
        }

        if (candidate.getVersion() == null
                || baseMapper.linkConvertedEngineerForTenant(candidateId, engineerId, tenantId,
                candidate.getVersion()) != 1) {
            throw BusinessException.of(409, "error.candidate.alreadyLinked");
        }
    }
}
