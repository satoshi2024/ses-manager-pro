package com.ses.service.training;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.certificationlearninggap.TrainingCourseMasterView;
import com.ses.dto.certificationlearninggap.TrainingCourseSkillView;
import com.ses.entity.SkillTag;
import com.ses.entity.TrainingCourse;
import com.ses.entity.TrainingCourseSkill;
import com.ses.mapper.SkillTagMapper;
import com.ses.mapper.TrainingCourseMapper;
import com.ses.mapper.TrainingCourseSkillMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** course/provider/cost/period/capacityとcanonical skill対象を一つのtransactionで管理する。 */
@Service
public class TrainingCourseMasterServiceImpl implements TrainingCourseMasterService {

    private final TrainingCourseMapper courseMapper;
    private final TrainingCourseSkillMapper courseSkillMapper;
    private final SkillTagMapper skillTagMapper;

    public TrainingCourseMasterServiceImpl(TrainingCourseMapper courseMapper,
                                           TrainingCourseSkillMapper courseSkillMapper,
                                           SkillTagMapper skillTagMapper) {
        this.courseMapper = courseMapper;
        this.courseSkillMapper = courseSkillMapper;
        this.skillTagMapper = skillTagMapper;
    }

    @Override
    public List<TrainingCourseMasterView> list(boolean includeInactive) {
        LambdaQueryWrapper<TrainingCourse> query = new LambdaQueryWrapper<TrainingCourse>()
                .orderByAsc(TrainingCourse::getName).orderByAsc(TrainingCourse::getId);
        if (!includeInactive) {
            query.eq(TrainingCourse::getActiveFlag, 1);
        }
        return courseMapper.selectList(query).stream().map(this::toView).toList();
    }

    @Override
    public TrainingCourseMasterView get(Long id) {
        return toView(requireCourse(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TrainingCourse create(TrainingCourseCommand command, Long actorUserId) {
        validate(command);
        List<Long> skillIds = validateSkillIds(command.requiredSkillIds());
        TrainingCourse course = new TrainingCourse();
        course.setTenantId(defaultTenant(command.tenantId()));
        copyFields(course, command);
        course.setActiveFlag(command.activeFlag() == null ? 1 : command.activeFlag());
        course.setVersion(0);
        course.setCreatedBy(actorUserId);
        course.setUpdatedBy(actorUserId);
        courseMapper.insert(course);
        replaceSkills(course, skillIds);
        return course;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TrainingCourse update(Long id, TrainingCourseCommand command, Long actorUserId) {
        validate(command);
        List<Long> skillIds = validateSkillIds(command.requiredSkillIds());
        TrainingCourse course = requireCourse(id);
        if (command.version() != null && !Objects.equals(command.version(), course.getVersion())) {
            throw BusinessException.of(409, "training.course.optimisticLock");
        }
        copyFields(course, command);
        if (command.activeFlag() != null) {
            course.setActiveFlag(command.activeFlag());
        }
        course.setUpdatedBy(actorUserId);
        if (courseMapper.updateById(course) != 1) {
            throw BusinessException.of(409, "training.course.optimisticLock");
        }
        replaceSkills(course, skillIds);
        return course;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TrainingCourse deactivate(Long id, Long actorUserId) {
        TrainingCourse course = requireCourse(id);
        course.setActiveFlag(0);
        course.setUpdatedBy(actorUserId);
        if (courseMapper.updateById(course) != 1) {
            throw BusinessException.of(409, "training.course.optimisticLock");
        }
        return course;
    }

    private TrainingCourse requireCourse(Long id) {
        TrainingCourse course = id == null ? null : courseMapper.selectById(id);
        if (course == null) {
            throw BusinessException.of(404, "training.course.notFound");
        }
        return course;
    }

    private void validate(TrainingCourseCommand command) {
        if (command == null || !StringUtils.hasText(command.provider()) || !StringUtils.hasText(command.name())) {
            throw BusinessException.of(400, "training.course.invalid");
        }
        BigDecimal cost = command.costJpy();
        if (cost == null || cost.signum() < 0 || cost.scale() > 0
                || (command.periodDays() != null && command.periodDays() < 0)
                || (command.capacity() != null && command.capacity() < 0)) {
            throw BusinessException.of(400, "training.course.invalid");
        }
    }

    private List<Long> validateSkillIds(List<Long> requested) {
        List<Long> ids = requested == null ? List.of() : requested.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return ids;
        }
        List<SkillTag> tags = skillTagMapper.selectBatchIds(ids);
        if (tags == null || tags.size() != ids.size()) {
            throw BusinessException.of(400, "training.course.unknownSkill");
        }
        return ids;
    }

    private void copyFields(TrainingCourse course, TrainingCourseCommand command) {
        course.setProvider(command.provider());
        course.setName(command.name());
        course.setDescription(command.description());
        course.setCostJpy(command.costJpy());
        course.setPeriodDays(command.periodDays());
        course.setCapacity(command.capacity());
    }

    private void replaceSkills(TrainingCourse course, List<Long> skillIds) {
        courseSkillMapper.delete(new LambdaQueryWrapper<TrainingCourseSkill>()
                .eq(TrainingCourseSkill::getCourseId, course.getId()));
        for (Long skillId : skillIds) {
            TrainingCourseSkill relation = new TrainingCourseSkill();
            relation.setTenantId(defaultTenant(course.getTenantId()));
            relation.setCourseId(course.getId());
            relation.setSkillId(skillId);
            relation.setRequiredFlag(1);
            courseSkillMapper.insert(relation);
        }
    }

    private TrainingCourseMasterView toView(TrainingCourse course) {
        List<TrainingCourseSkill> relations = courseSkillMapper.selectList(new LambdaQueryWrapper<TrainingCourseSkill>()
                .eq(TrainingCourseSkill::getCourseId, course.getId())
                .eq(TrainingCourseSkill::getRequiredFlag, 1)
                .orderByAsc(TrainingCourseSkill::getSkillId));
        Map<Long, SkillTag> tags = new LinkedHashMap<>();
        List<Long> ids = relations.stream().map(TrainingCourseSkill::getSkillId).filter(Objects::nonNull).distinct().toList();
        if (!ids.isEmpty()) {
            List<SkillTag> loaded = skillTagMapper.selectBatchIds(ids);
            if (loaded != null) {
                loaded.forEach(tag -> tags.put(tag.getId(), tag));
            }
        }
        List<TrainingCourseSkillView> skills = relations.stream().map(relation -> {
            SkillTag tag = tags.get(relation.getSkillId());
            return new TrainingCourseSkillView(relation.getSkillId(), tag == null ? null : tag.getSkillName(),
                    tag == null ? null : tag.getCategory(), relation.getTargetLevel(),
                    Integer.valueOf(1).equals(relation.getRequiredFlag()));
        }).toList();
        return new TrainingCourseMasterView(course.getId(), course.getTenantId(), course.getProvider(), course.getName(),
                course.getDescription(), course.getCostJpy(), course.getPeriodDays(), course.getCapacity(),
                course.getActiveFlag(), course.getVersion(), skills);
    }

    private String defaultTenant(String tenantId) {
        return StringUtils.hasText(tenantId) ? tenantId : "default";
    }
}
