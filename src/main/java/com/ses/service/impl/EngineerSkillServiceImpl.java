package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.dto.engineer.EngineerSkillDetailDto;
import com.ses.entity.EngineerSkill;
import com.ses.mapper.EngineerSkillMapper;
import com.ses.service.EngineerSkillService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
public class EngineerSkillServiceImpl extends ServiceImpl<com.ses.mapper.EngineerSkillMapper, EngineerSkill> implements EngineerSkillService {

    private final com.ses.mapper.EngineerMapper engineerMapper;
    private final com.ses.mapper.SkillTagMapper skillTagMapper;

    public EngineerSkillServiceImpl(com.ses.mapper.EngineerMapper engineerMapper, com.ses.mapper.SkillTagMapper skillTagMapper) {
        this.engineerMapper = engineerMapper;
        this.skillTagMapper = skillTagMapper;
    }

    @Override
    public List<EngineerSkillDetailDto> listDetail(Long engineerId) {
        return baseMapper.selectDetailByEngineerId(engineerId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceSkills(Long engineerId, List<EngineerSkill> skills) {
        // Validate Engineer existence
        if (engineerMapper.selectById(engineerId) == null) {
            throw com.ses.common.exception.BusinessException.of(404, "error.engineer.notFound");
        }

        if (skills != null && !skills.isEmpty()) {
            List<Long> skillIds = skills.stream()
                    .map(EngineerSkill::getSkillId)
                    .distinct()
                    .collect(Collectors.toList());
            List<com.ses.entity.SkillTag> existingSkills = skillTagMapper.selectBatchIds(skillIds);
            if (existingSkills.size() != skillIds.size()) {
                throw com.ses.common.exception.BusinessException.of(400, "error.skill.notFound");
            }
        }

        // 1. Delete all existing skills for the engineer
        remove(new LambdaQueryWrapper<EngineerSkill>().eq(EngineerSkill::getEngineerId, engineerId));

        if (skills == null || skills.isEmpty()) {
            return;
        }

        // 2. Distinct by skillId and enforce engineerId
        List<EngineerSkill> distinctSkills = skills.stream()
                .filter(distinctByKey(EngineerSkill::getSkillId))
                .peek(skill -> skill.setEngineerId(engineerId))
                .collect(Collectors.toList());

        // 3. Batch insert
        saveBatch(distinctSkills);
    }

    private static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
        Map<Object, Boolean> seen = new ConcurrentHashMap<>();
        return t -> seen.putIfAbsent(keyExtractor.apply(t), Boolean.TRUE) == null;
    }
}
