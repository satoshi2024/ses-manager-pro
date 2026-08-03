package com.ses.service.approval;

import java.util.List;

/**
 * route_snapshot_jsonへ書き込む1step分。承認者は申請時点で具体user idまで解決済み
 * （design §6.1: 承認者はroute snapshotから解決し、後のroute変更で変えない）。
 * stepは全slotを必要とし、各slotは候補者のうちrequiredCount名で充足する。
 */
public record RouteStepGroup(
        int stepNo,
        Integer slaHours,
        List<Long> approverUserIds,
        List<RouteSlot> slots
) {
    public RouteStepGroup {
        approverUserIds = approverUserIds == null ? List.of() : List.copyOf(approverUserIds);
        slots = slots == null ? List.of() : List.copyOf(slots);
    }

    /** 旧snapshot/test fixture互換。候補者一覧を1つのslotとして扱う。 */
    public RouteStepGroup(int stepNo, Integer slaHours, List<Long> approverUserIds) {
        this(stepNo, slaHours, approverUserIds,
                approverUserIds == null || approverUserIds.isEmpty()
                        ? List.of()
                        : List.of(new RouteSlot(0, "RESOLVED", approverUserIds, 1)));
    }

    /** 旧形式snapshot（slotsフィールドなし）を読み込んだ場合の移行互換。 */
    @Override
    public List<RouteSlot> slots() {
        if (!slots.isEmpty()) {
            return slots;
        }
        if (approverUserIds.isEmpty()) {
            return List.of();
        }
        return List.of(new RouteSlot(0, "RESOLVED", approverUserIds, 1));
    }
}
