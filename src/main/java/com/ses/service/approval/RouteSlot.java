package com.ses.service.approval;

import java.util.List;

/** route step内の1承認slot。候補者のうちrequiredCount名で充足する。 */
public record RouteSlot(
        int slotIndex,
        String approverType,
        List<Long> candidateUserIds,
        int requiredCount
) {
    public RouteSlot {
        candidateUserIds = candidateUserIds == null ? List.of() : List.copyOf(candidateUserIds);
    }
}
