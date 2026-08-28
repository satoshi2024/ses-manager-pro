package com.ses.service.training;

import com.ses.common.exception.BusinessException;
import com.ses.entity.SkillTag;
import com.ses.entity.TrainingCourse;
import com.ses.entity.TrainingCourseSkill;
import com.ses.mapper.SkillTagMapper;
import com.ses.mapper.TrainingCourseMapper;
import com.ses.mapper.TrainingCourseSkillMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrainingCourseMasterServiceImplTest {

    @Mock private TrainingCourseMapper courseMapper;
    @Mock private TrainingCourseSkillMapper courseSkillMapper;
    @Mock private SkillTagMapper skillTagMapper;
    private TrainingCourseMasterService service;

    @BeforeEach
    void setUp() {
        service = new TrainingCourseMasterServiceImpl(courseMapper, courseSkillMapper, skillTagMapper);
    }

    @Test
    void courseとcanonicalSkillを登録更新無効化できる() {
        when(skillTagMapper.selectBatchIds(List.of(5L))).thenReturn(List.of(skill(5L)));
        when(courseMapper.updateById(any(TrainingCourse.class))).thenReturn(1);
        TrainingCourseMasterService.TrainingCourseCommand command = command(List.of(5L), 0);

        TrainingCourse created = service.create(command, 7L);
        TrainingCourse current = course(11L, 1);
        when(courseMapper.selectById(11L)).thenReturn(current);
        TrainingCourse updated = service.update(11L, command(List.of(5L), 1), 7L);
        TrainingCourse disabled = service.deactivate(11L, 7L);

        assertEquals("AWS研修", created.getName());
        assertEquals(11L, updated.getId());
        assertEquals(0, disabled.getActiveFlag());
        verify(courseSkillMapper, org.mockito.Mockito.times(2)).insert(any(TrainingCourseSkill.class));
        verify(courseSkillMapper, org.mockito.Mockito.times(2)).delete(any());
    }

    @Test
    void 存在しないcanonicalSkillはcourse登録を拒否する() {
        when(skillTagMapper.selectBatchIds(List.of(999L))).thenReturn(List.of());
        assertThrows(BusinessException.class, () -> service.create(command(List.of(999L), 0), 7L));
    }

    @Test
    void 金額負数とversion不一致は拒否する() {
        TrainingCourseMasterService.TrainingCourseCommand invalid = new TrainingCourseMasterService.TrainingCourseCommand(
                "default", "provider", "name", null, new BigDecimal("-1"), null, null, 1, null, List.of());
        assertThrows(BusinessException.class, () -> service.create(invalid, 7L));

        when(courseMapper.selectById(11L)).thenReturn(course(11L, 2));
        assertThrows(BusinessException.class, () -> service.update(11L, command(List.of(), 1), 7L));
    }

    private TrainingCourseMasterService.TrainingCourseCommand command(List<Long> skills, Integer version) {
        return new TrainingCourseMasterService.TrainingCourseCommand("default", "AWS", "AWS研修", "説明",
                new BigDecimal("10000"), 3, 10, 1, version, skills);
    }

    private SkillTag skill(Long id) {
        SkillTag tag = new SkillTag(); tag.setId(id); tag.setSkillName("AWS"); tag.setCategory("Cloud"); return tag;
    }

    private TrainingCourse course(Long id, Integer version) {
        TrainingCourse course = new TrainingCourse(); course.setId(id); course.setTenantId("default");
        course.setName("旧course"); course.setProvider("provider"); course.setCostJpy(BigDecimal.ONE);
        course.setActiveFlag(1); course.setVersion(version); return course;
    }
}
