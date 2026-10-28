package com.ses.service.lifecycle.evidence;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * discovery済みのlifecycle証跡依存先を型付き・重複排除で並べるlock順。
 * これは依存先一覧を整列する値であり、DB lock取得は行わない。epoch schemaとtransaction
 * coordinatorが整うまで、DB実行と新transactionでのretryは未接続である。
 */
public record LifecycleLockPlan(List<LockKey> locks) {

    public enum Rank {
        GLOBAL_AUTHORIZATION_EPOCH,
        TENANT_AUTHORIZATION_EPOCH,
        SYS_USER,
        ACTOR_AUTHORIZATION_EPOCH,
        LIFECYCLE_CASE,
        LIFECYCLE_TASK,
        ORGANIZATION,
        ENGINEER,
        CUSTOMER,
        CONTRACT,
        PROPOSAL,
        PROJECT,
        SALES_ACTIVITY,
        ASSET,
        ASSET_ASSIGNMENT,
        ACCEPTANCE,
        SERVICE_REQUEST,
        DOCUMENT,
        DOCUMENT_VERSION,
        DOCUMENT_LINK,
        DOCUMENT_DISPOSAL_REQUEST
    }

    public record LockKey(Rank rank, String partition, long id) {
        public LockKey {
            Objects.requireNonNull(rank, "rank");
            partition = partition == null ? "" : partition;
            if (id <= 0) {
                throw new IllegalArgumentException("lock対象IDは正数で指定してください");
            }
        }
    }

    public LifecycleLockPlan {
        Objects.requireNonNull(locks, "locks");
        locks = locks.stream().map(key -> Objects.requireNonNull(key, "lockKey"))
                .sorted(Comparator.comparing(LockKey::rank)
                        .thenComparing(LockKey::partition)
                        .thenComparingLong(LockKey::id))
                .collect(LinkedHashMap<LockKey, LockKey>::new,
                        (unique, key) -> unique.putIfAbsent(key, key),
                        LinkedHashMap::putAll)
                .values().stream().toList();
    }

    public static LifecycleLockPlan from(Collection<LockKey> discoveredDependencies) {
        Objects.requireNonNull(discoveredDependencies, "discoveredDependencies");
        return new LifecycleLockPlan(List.copyOf(discoveredDependencies));
    }

    /** 取得済みlock集合が再discovery結果と一致しない場合はtransaction全体を中止する。 */
    public void requireSameDependencies(LifecycleLockPlan currentPlan) {
        Objects.requireNonNull(currentPlan, "currentPlan");
        if (!locks.equals(currentPlan.locks)) {
            throw new DependencyChangedException();
        }
    }

    /** 依存差分はtransaction全体を中止し、新transactionで再discoveryする。transaction内で追加lock retryしない。 */
    public static final class DependencyChangedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public DependencyChangedException() {
            super("ライフサイクル認可依存関係がdiscovery後に変更されました。transaction全体をrollbackしてください");
        }
    }
}
