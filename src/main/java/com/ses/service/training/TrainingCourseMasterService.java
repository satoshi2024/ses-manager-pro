package com.ses.service.training;

import com.ses.dto.certificationlearninggap.TrainingCourseMasterView;

import java.util.List;

/** course catalogとcanonical skill relationを所有する管理service。 */
public interface TrainingCourseMasterService {

    List<TrainingCourseMasterView> list(boolean includeInactive);

    TrainingCourseMasterView get(Long id);

    TrainingCourseMasterView create(TrainingCourseCommand command, Long actorUserId);

    TrainingCourseMasterView update(Long id, TrainingCourseCommand command, Long actorUserId);

    TrainingCourseMasterView deactivate(Long id, Integer expectedVersion, Long actorUserId);

    record TrainingCourseCommand(String tenantId, String provider, String name, String description,
                                 java.math.BigDecimal costJpy, Integer periodDays, Integer capacity,
                                 Integer activeFlag, Integer version, List<Long> requiredSkillIds) {
    }
}
