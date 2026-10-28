package com.ses.service.repair;

import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;

/**
 * 初回実行時における確定載体選定コンポーネント (Initial Carrier Selector)
 * <p>
 * 注意: 本選定規則は初回書き込み時（全キューが PENDING の状態）にのみ適用され、
 * 重試（Retry）時には決して再評価してはならない。
 * </p>
 */
public final class InitialCarrierSelector {

    private InitialCarrierSelector() {}

    /**
     * 初回実行時における代表載体キューを決定論的に選定する。
     *
     * @param parentQueue 親インボイスキュー行（存在しない場合は null）
     * @param participatingQueues 本修復トランザクションで参加する PENDING キュー集合
     * @return 選定された代表載体キュー
     */
    public static ScopeRepairQueueSnapshot selectInitialCarrier(
        ScopeRepairQueueSnapshot parentQueue,
        Collection<ScopeRepairQueueSnapshot> participatingQueues
    ) {
        Objects.requireNonNull(participatingQueues, "participatingQueues must not be null");
        if (participatingQueues.isEmpty()) {
            throw new IllegalArgumentException("参加キュー集合が空です");
        }
        for (ScopeRepairQueueSnapshot q : participatingQueues) {
            if (!"PENDING".equals(q.status())) {
                throw new IllegalArgumentException("参加キュー集合にPENDING以外のキューが含まれています: id="
                    + q.id() + ", status=" + q.status());
            }
        }

        // 親キューが存在し、かつ PENDING で参加キュー集合に含まれる場合は親キューを最優先
        if (parentQueue != null && "PENDING".equals(parentQueue.status())) {
            boolean containsParent = participatingQueues.stream().anyMatch(q -> q.id().equals(parentQueue.id()));
            if (containsParent) {
                return parentQueue;
            }
        }

        // それ以外（親が NO_QUEUE または既に RESOLVED 等）は、参加キュー集合中の最小 ID を選定
        return participatingQueues.stream()
            .min(Comparator.comparing(ScopeRepairQueueSnapshot::id))
            .orElseThrow(() -> new IllegalStateException("代表載体キューを選定できませんでした"));
    }
}
