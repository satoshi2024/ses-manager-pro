package com.ses.dto.skillgap;

import com.ses.service.SkillGapService;

import java.time.LocalDate;

/** skill gap計算の再現可能な入力。 */
public record SkillGapRequest(
        Long engineerId,
        Long projectId,
        LocalDate asOf,
        LocalDate periodFrom,
        LocalDate periodTo,
        SkillGapService.DemandSource demandSource,
        Long createdBy) {

    public SkillGapRequest(Long engineerId, Long projectId, LocalDate asOf,
                           SkillGapService.DemandSource demandSource) {
        this(engineerId, projectId, asOf, asOf, asOf, demandSource, null);
    }
}
