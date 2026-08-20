package com.ses.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.AiArtifactVersion;
import com.ses.entity.AiEvaluation;
import com.ses.mapper.AiArtifactVersionMapper;
import com.ses.mapper.AiEvaluationMapper;
import com.ses.service.ai.AiArtifactVersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AiArtifactVersionServiceImpl
        extends ServiceImpl<AiArtifactVersionMapper, AiArtifactVersion>
        implements AiArtifactVersionService {

    public static final String STATUS_SHADOW = "SHADOW";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_RETIRED = "RETIRED";

    private final AiEvaluationMapper evaluationMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiArtifactVersion promoteToActive(Long candidateId) {
        if (candidateId == null) {
            throw new BusinessException("昇格対象が指定されていません");
        }
        AiArtifactVersion candidate = getById(candidateId);
        if (candidate == null) {
            throw new BusinessException(404, "artifact version が見つかりません");
        }
        if (!STATUS_SHADOW.equals(candidate.getStatus())) {
            throw new BusinessException(409, "SHADOW 以外は ACTIVE へ昇格できません");
        }
        LocalDateTime now = LocalDateTime.now();
        AiArtifactVersion current = getOne(new LambdaQueryWrapper<AiArtifactVersion>()
                .eq(AiArtifactVersion::getUseCase, candidate.getUseCase())
                .eq(AiArtifactVersion::getStatus, STATUS_ACTIVE), false);
        if (current != null) {
            int retired = baseMapper.casRetireActive(
                    current.getId(), current.getStatusVersion(), now);
            if (retired != 1) {
                throw new BusinessException(409, "ACTIVE versionは他の操作で変更されました");
            }
        }
        try {
            int activated = baseMapper.casActivateShadow(
                    candidate.getId(), candidate.getStatusVersion(), now);
            if (activated != 1) {
                throw new BusinessException(409, "昇格対象がSHADOWではありません");
            }
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(409, "use caseあたりACTIVEは1つだけです");
        }
        return getById(candidateId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiArtifactVersion promoteApproved(Long evaluationId) {
        if (evaluationId == null) {
            throw new BusinessException("評価が指定されていません");
        }
        if (!"管理者".equals(SecurityUtils.currentRole())) {
            throw new BusinessException(403, "version昇格は管理者のみです");
        }
        AiEvaluation evaluation = evaluationMapper.selectById(evaluationId);
        if (evaluation == null) {
            throw new BusinessException(404, "評価が見つかりません");
        }
        if (!"PASSED".equals(evaluation.getStatus())) {
            throw new BusinessException(409, "PASSED の評価だけが承認できます");
        }
        evaluation.setStatus("APPROVED");
        evaluation.setApprovedBy(SecurityUtils.currentUserId());
        evaluation.setApprovedAt(LocalDateTime.now());
        evaluation.setStatusVersion(
                evaluation.getStatusVersion() == null ? 1 : evaluation.getStatusVersion() + 1);
        evaluationMapper.updateById(evaluation);
        return promoteToActive(evaluation.getCandidateVersionId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiArtifactVersion rollbackTo(Long versionId) {
        if (versionId == null) {
            throw new BusinessException("rollback先が指定されていません");
        }
        AiArtifactVersion target = getById(versionId);
        if (target == null) {
            throw new BusinessException(404, "artifact version が見つかりません");
        }
        if (!STATUS_RETIRED.equals(target.getStatus())) {
            throw new BusinessException(409, "RETIRED 以外へは rollback できません");
        }
        LocalDateTime now = LocalDateTime.now();
        AiArtifactVersion current = getOne(new LambdaQueryWrapper<AiArtifactVersion>()
                .eq(AiArtifactVersion::getUseCase, target.getUseCase())
                .eq(AiArtifactVersion::getStatus, STATUS_ACTIVE), false);
        if (current != null) {
            int retired = baseMapper.casRetireActive(
                    current.getId(), current.getStatusVersion(), now);
            if (retired != 1) {
                throw new BusinessException(409, "ACTIVE versionは他の操作で変更されました");
            }
        }
        try {
            int activated = baseMapper.casActivateRetired(
                    target.getId(), target.getStatusVersion(), now);
            if (activated != 1) {
                throw new BusinessException(409, "rollback対象がRETIREDではありません");
            }
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(409, "use caseあたりACTIVEは1つだけです");
        }
        return getById(versionId);
    }
}
