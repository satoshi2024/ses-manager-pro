package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.dto.project.ProjectSkillDetailDto;
import com.ses.entity.ProjectSkill;
import com.ses.mapper.ProjectSkillMapper;
import com.ses.service.ProjectSkillService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
public class ProjectSkillServiceImpl extends ServiceImpl<ProjectSkillMapper, ProjectSkill> implements ProjectSkillService {

    @Override
    public List<ProjectSkillDetailDto> listDetail(Long projectId) {
        return baseMapper.selectDetailByProjectId(projectId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceSkills(Long projectId, List<ProjectSkill> skills) {
        // 1. Delete existing skills for this project
        remove(new LambdaQueryWrapper<ProjectSkill>().eq(ProjectSkill::getProjectId, projectId));

        if (skills == null || skills.isEmpty()) {
            return;
        }

        // 2. Distinct by skillId and enforce projectId
        List<ProjectSkill> distinctSkills = skills.stream()
                .filter(distinctByKey(ProjectSkill::getSkillId))
                .peek(skill -> skill.setProjectId(projectId))
                .collect(Collectors.toList());

        // 3. Batch insert
        saveBatch(distinctSkills);
    }

    private static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
        Map<Object, Boolean> seen = new ConcurrentHashMap<>();
        return t -> seen.putIfAbsent(keyExtractor.apply(t), Boolean.TRUE) == null;
    }
}
