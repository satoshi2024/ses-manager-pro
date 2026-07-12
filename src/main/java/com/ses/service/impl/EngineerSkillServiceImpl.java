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
public class EngineerSkillServiceImpl extends ServiceImpl<EngineerSkillMapper, EngineerSkill> implements EngineerSkillService {

    @Override
    public List<EngineerSkillDetailDto> listDetail(Long engineerId) {
        return baseMapper.selectDetailByEngineerId(engineerId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceSkills(Long engineerId, List<EngineerSkill> skills) {
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
