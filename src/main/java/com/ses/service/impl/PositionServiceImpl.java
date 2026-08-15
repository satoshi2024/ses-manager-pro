package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.ProjectPosition;
import com.ses.entity.Project;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.ProjectPositionMapper;
import com.ses.service.staffing.PositionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.ses.entity.ProjectPosition.STATUS_CANDIDATE;
import static com.ses.entity.ProjectPosition.STATUS_CANCELLED;
import static com.ses.entity.ProjectPosition.STATUS_FILLED;
import static com.ses.entity.ProjectPosition.STATUS_HOLD;
import static com.ses.entity.ProjectPosition.STATUS_RECRUITING;

/**
 * 案件ポジションの状態機械実装。遷移は状態CAS（status+version条件付きUPDATE）で実行する。
 * 状態機械はdesign §5.4の確定済み表を唯一の正とする。
 */
@Service
@RequiredArgsConstructor
public class PositionServiceImpl implements PositionService {

    /** 許可遷移表（design §5.4）。この表を唯一の正とし、呼出側で個別に判定しない。 */
    private static final Map<String, Set<String>> TRANSITIONS = new LinkedHashMap<>();

    static {
        TRANSITIONS.put(STATUS_RECRUITING, Set.of(STATUS_CANDIDATE, STATUS_CANCELLED));
        TRANSITIONS.put(STATUS_CANDIDATE, Set.of(STATUS_FILLED, STATUS_HOLD, STATUS_CANCELLED));
        TRANSITIONS.put(STATUS_FILLED, Set.of(STATUS_RECRUITING));
        TRANSITIONS.put(STATUS_HOLD, Set.of(STATUS_RECRUITING));
        TRANSITIONS.put(STATUS_CANCELLED, Set.of(STATUS_RECRUITING));
    }

    private final ProjectPositionMapper positionMapper;
    private final ProjectMapper projectMapper;

    @Override
    @Transactional
    public ProjectPosition create(ProjectPosition position) {
        requireProject(position.getProjectId());
        position.setId(null);
        position.setStatus(STATUS_RECRUITING);
        position.setVersion(0);
        positionMapper.insert(position);
        return position;
    }

    @Override
    @Transactional
    public ProjectPosition update(ProjectPosition position) {
        if (position.getId() == null) {
            throw BusinessException.of(404, "error.staffing.positionNotFound");
        }
        ProjectPosition existing = require(position.getId());
        position.setProjectId(existing.getProjectId());
        position.setStatus(existing.getStatus());
        int rows = positionMapper.updateById(position);
        if (rows == 0) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        return positionMapper.selectById(position.getId());
    }

    @Override
    @Transactional
    public ProjectPosition changeStatus(Long id, String toStatus) {
        ProjectPosition existing = require(id);
        Set<String> allowed = TRANSITIONS.getOrDefault(existing.getStatus(), Set.of());
        if (!allowed.contains(toStatus)) {
            throw BusinessException.of(400, "error.staffing.invalidTransition",
                    existing.getStatus(), toStatus);
        }
        int version = value(existing.getVersion());
        int rows = positionMapper.update(null, new LambdaUpdateWrapper<ProjectPosition>()
                .set(ProjectPosition::getStatus, toStatus)
                .set(ProjectPosition::getVersion, version + 1)
                .set(ProjectPosition::getUpdatedAt, LocalDateTime.now())
                .eq(ProjectPosition::getId, id)
                .eq(ProjectPosition::getStatus, existing.getStatus())
                .eq(ProjectPosition::getVersion, version));
        if (rows != 1) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        return positionMapper.selectById(id);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        ProjectPosition existing = require(id);
        if (STATUS_FILLED.equals(existing.getStatus())) {
            throw BusinessException.of(400, "error.staffing.positionFilled");
        }
        positionMapper.deleteById(id);
    }

    @Override
    public ProjectPosition get(Long id) {
        return require(id);
    }

    @Override
    public List<ProjectPosition> listByProject(Long projectId) {
        return positionMapper.selectList(new LambdaQueryWrapper<ProjectPosition>()
                .eq(ProjectPosition::getProjectId, projectId)
                .orderByAsc(ProjectPosition::getPositionNo));
    }

    private void requireProject(Long projectId) {
        if (projectId == null || projectMapper.selectById(projectId) == null) {
            throw BusinessException.of(400, "error.staffing.projectRequired");
        }
    }

    private ProjectPosition require(Long id) {
        ProjectPosition position = id == null ? null : positionMapper.selectById(id);
        if (position == null) {
            throw BusinessException.of(404, "error.staffing.positionNotFound");
        }
        return position;
    }

    private int value(Integer version) {
        return version == null ? 0 : version;
    }
}
