package com.ses.dto.skillgap;

import java.util.List;

/** 1 canonical/unknown skillに対するrule-based gap。 */
public record SkillGapItem(
        String key,
        Long canonicalSkillId,
        String requestedName,
        String canonicalName,
        String resolution,
        String requiredLevel,
        String suppliedLevel,
        Integer requiredCount,
        Integer evidenceCount,
        boolean mandatory,
        boolean gap,
        boolean unknown,
        String source,
        String precedence,
        List<Long> demandEventIds,
        List<Long> supplyEventIds) {
}
