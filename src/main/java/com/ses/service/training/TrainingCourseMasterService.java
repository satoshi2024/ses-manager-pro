package com.ses.service.training;

import com.ses.dto.certificationlearninggap.TrainingCourseMasterView;
import com.ses.entity.TrainingCourse;

import java.util.List;

/** course catalogとcanonical skill relationを所有する管理service。 */
public interface TrainingCourseMasterService {

    List<TrainingCourseMasterView> list(boolean includeInactive);

    TrainingCourseMasterView get(Long id);

    TrainingCourse create(TrainingCourseCommand command, Long actorUserId);

    TrainingCourse update(Long id, TrainingCourseCommand command, Long actorUserId);

    TrainingCourse deactivate(Long id, Long actorUserId);

    record TrainingCourseCommand(String tenantId, String provider, String name, String description,
                                 java.math.BigDecimal costJpy, Integer periodDays, Integer capacity,
                                 Integer activeFlag, Integer version, List<Long> requiredSkillIds) {
    }
}
