package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
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
        if (!StringUtils.hasText(candidate.getCurrentStage())) {
            candidate.setCurrentStage(StatusConstants.CANDIDATE_STAGE_APPLIED);
        } else {
            validateStage(candidate.getCurrentStage());
        }
        return super.save(candidate);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStage(Long candidateId, String newStage, String reason, String remarks) {
        Candidate candidate = this.getById(candidateId);
        if (candidate == null) {
            throw BusinessException.of("error.candidate.notFound");
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
        Candidate update = new Candidate();
        update.setId(candidateId);
        update.setCurrentStage(newStage);
        this.updateById(update);
    }

    private void validateStage(String stage) {
        if (!ALLOWED_STAGES.contains(stage)) {
            throw BusinessException.of("error.candidate.stageInvalid", stage);
        }
    }

    @Override
    public List<CandidateActivity> getActivities(Long candidateId) {
        LambdaQueryWrapper<CandidateActivity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CandidateActivity::getCandidateId, candidateId)
               .orderByDesc(CandidateActivity::getChangedAt);
        return candidateActivityMapper.selectList(wrapper);
    }

    @Override
    public CandidateEngineerInitialDto getEngineerInitialDto(Long candidateId) {
        Candidate candidate = this.getById(candidateId);
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
        Candidate candidate = this.getById(candidateId);
        if (candidate == null) {
            throw BusinessException.of("error.candidate.notFound");
        }
        if (!StatusConstants.CANDIDATE_STAGE_HIRED.equals(candidate.getCurrentStage())) {
            throw BusinessException.of("error.candidate.notHiredStage");
        }
        
        if (engineerId == null) {
            throw BusinessException.of(400, "error.candidate.invalidEngineerId");
        }
        com.ses.entity.Engineer eng = engineerMapper.selectById(engineerId);
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
        LambdaQueryWrapper<Candidate> dupCheck = new LambdaQueryWrapper<>();
        dupCheck.eq(Candidate::getConvertedEngineerId, engineerId);
        if (this.count(dupCheck) > 0) {
            throw BusinessException.of(409, "error.candidate.alreadyLinked");
        }

        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Candidate> updateWrapper = new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
        updateWrapper.eq(Candidate::getId, candidateId)
                     .isNull(Candidate::getConvertedEngineerId)
                     .set(Candidate::getConvertedEngineerId, engineerId);
        
        boolean updated = this.update(updateWrapper);
        if (!updated) {
            // Concurrent modification check
            Candidate current = this.getById(candidateId);
            if (current != null && engineerId.equals(current.getConvertedEngineerId())) {
                return;
            }
            throw BusinessException.of(409, "error.candidate.alreadyLinked");
        }
    }
}
