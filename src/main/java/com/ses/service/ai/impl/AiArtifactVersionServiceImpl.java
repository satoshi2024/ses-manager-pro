package com.ses.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.entity.AiArtifactVersion;
import com.ses.mapper.AiArtifactVersionMapper;
import com.ses.service.ai.AiArtifactVersionService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AiArtifactVersionServiceImpl
        extends ServiceImpl<AiArtifactVersionMapper, AiArtifactVersion>
        implements AiArtifactVersionService {

    public static final String STATUS_SHADOW = "SHADOW";
    public static final String STATUS_ACTIVE = "ACTIVE";

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
}
