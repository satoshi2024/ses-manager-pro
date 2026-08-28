package com.ses.dto.skillgap;

import com.ses.service.SkillGapService;

import java.time.LocalDate;
import java.util.List;

/**
 * rule-based gapの結果。statusがOKでない場合はcurrent projectionへfallbackせず、理由だけを返す。
 */
public record SkillGapResult(
        String status,
        String unavailableReason,
        LocalDate asOf,
        LocalDate periodFrom,
        LocalDate periodTo,
        Long engineerId,
        Long projectId,
        SkillGapService.DemandSource demandSource,
        List<SkillGapItem> items,
        List<String> warnings,
        Long snapshotId) {

    public static SkillGapResult unavailable(SkillGapRequest request, String reason) {
        return new SkillGapResult(SkillGapService.STATUS_HISTORICAL_DATA_UNAVAILABLE, reason,
                request.asOf(), request.periodFrom(), request.periodTo(), request.engineerId(),
                request.projectId(), request.demandSource(), List.of(), List.of(reason), null);
    }

    public SkillGapResult withSnapshotId(Long id) {
        return new SkillGapResult(status, unavailableReason, asOf, periodFrom, periodTo, engineerId,
                projectId, demandSource, items, warnings, id);
    }
}
