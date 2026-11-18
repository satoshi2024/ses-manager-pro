package com.ses.service.lifecycle.evidence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * discovery済みのlifecycle証跡依存先を型付き・重複排除で並べるlock順（NF01細粒度epoch計画 §3.1・§3.3）。
 * これは依存先一覧を整列する値であり、DB lock取得は行わない（取得は {@link LifecycleLockExecutor}）。
 *
 * <ul>
 *   <li>並びは {@link Rank} の宣言順（§3.1の順番号）→ partition → ID の昇順。</li>
 *   <li>同じ行（rank・partition・ID）は1つに畳み、どれかが {@link Mode#EXCLUSIVE} ならEXCLUSIVEにする。
 *       これにより実行時のS→X昇格を構造的に無くす（§3.5 規則2）。</li>
 *   <li>{@link #requireSameDependencies} はkeyとmodeの両方を比較する。</li>
 *   <li>partitionはtenant行ではtenant ID、全体epochと組織、tenant列を持たない子行・業務親では空文字。</li>
 * </ul>
 */
public record LifecycleLockPlan(List<LockKey> locks) {

    /** lockの種類。 */
    public enum Mode {
        /** {@code FOR SHARE}（CONFIRM attemptのreaderがanchor行に取る）。 */
        SHARED,
        /** {@code FOR UPDATE}（writer、および案件・タスク）。 */
        EXCLUSIVE
    }

    /** partitionの決め方。 */
    public enum Partitioning {
        /** 全体epochの1行（partitionは空文字、IDは1）。 */
        GLOBAL,
        /** tenant列で照合する行（partitionはtenant ID。必須）。 */
        TENANT,
        /** tenant列を持たない行（partitionは空文字）。 */
        UNPARTITIONED
    }

    /**
     * lock順（計画 §3.1）。宣言順がそのまま取得順である。旧 TENANT_AUTHORIZATION_EPOCH は廃止し（tenant epochを持たない）、
     * 旧 ACTOR_AUTHORIZATION_EPOCH は {@link #SYS_USER} 行へ統合した（actor epochは {@code sys_user} 行の列）。
     * {@code sharedAllowed} は §3.1 で reader が S を取る行（全体・利用者・組織・要員・文書・版）だけtrueである。
     */
    public enum Rank {
        /** 順1: {@code t_authorization_global_epoch}。 */
        GLOBAL_AUTHORIZATION_EPOCH(1, Partitioning.GLOBAL, true),
        /** 順2: {@code t_approval_request}。承認確定は申請行を最初にlockし、承認adapterがsys_userを書くためsys_userより前。 */
        APPROVAL_REQUEST(2, Partitioning.TENANT, false),
        /** 順3: {@code sys_user}（actor epoch同居）。 */
        SYS_USER(3, Partitioning.TENANT, true),
        /** 順4: {@code t_lifecycle_case}。 */
        LIFECYCLE_CASE(4, Partitioning.TENANT, false),
        /** 順5: {@code t_lifecycle_task}。必ず所属案件のX保持下で取る。partitionは案件のtenant。 */
        LIFECYCLE_TASK(5, Partitioning.TENANT, false),
        /** 順6: {@code m_organization_unit}（tenantに依存しない。祖先鎖はID昇順）。 */
        ORGANIZATION(6, Partitioning.UNPARTITIONED, true),
        /** 順7: {@code t_engineer}。 */
        ENGINEER(7, Partitioning.TENANT, true),
        /** 順8: {@code t_user_organization}（子行）。 */
        USER_ORGANIZATION(8, Partitioning.TENANT, false),
        /** 順9: {@code t_engineer_account_link}（子行）。 */
        ENGINEER_ACCOUNT_LINK(9, Partitioning.TENANT, false),
        /** 順10: {@code t_engineer_sales}（子行。tenant列なし）。 */
        ENGINEER_SALES(10, Partitioning.UNPARTITIONED, false),
        /** 順11: {@code t_engineer_accounting_history}（子行。tenant列なし）。 */
        ENGINEER_ACCOUNTING_HISTORY(11, Partitioning.UNPARTITIONED, false),
        /** 順12: {@code t_portal_user_engineer_link}（子行。epochなし）。 */
        PORTAL_ENGINEER_LINK(12, Partitioning.TENANT, false),
        /** 順13: {@code m_customer}。 */
        CUSTOMER(13, Partitioning.TENANT, false),
        /** 順13: {@code t_contract}。 */
        CONTRACT(13, Partitioning.TENANT, false),
        /** 順13: {@code t_proposal}（tenant列なし）。 */
        PROPOSAL(13, Partitioning.UNPARTITIONED, false),
        /** 順13: {@code t_project}（tenant列なし）。 */
        PROJECT(13, Partitioning.UNPARTITIONED, false),
        /** 順13: {@code t_sales_activity}（tenant列なし）。 */
        SALES_ACTIVITY(13, Partitioning.UNPARTITIONED, false),
        /** 順13: {@code m_asset}（tenant列なし）。資産は asset → assignment。 */
        ASSET(13, Partitioning.UNPARTITIONED, false),
        /** 順13: {@code t_asset_assignment}（tenant列なし）。 */
        ASSET_ASSIGNMENT(13, Partitioning.UNPARTITIONED, false),
        /** 順13: {@code t_acceptance}（tenant列なし）。 */
        ACCEPTANCE(13, Partitioning.UNPARTITIONED, false),
        /** 順13: {@code t_service_request}。 */
        SERVICE_REQUEST(13, Partitioning.TENANT, false),
        /** 順14: {@code t_external_account_reference}（退社完了時・退社後登録時の失効要求登録）。 */
        EXTERNAL_ACCOUNT_REFERENCE(14, Partitioning.TENANT, false),
        /** 順15: {@code t_document}。 */
        DOCUMENT(15, Partitioning.TENANT, true),
        /** 順16: {@code t_document_version}。 */
        DOCUMENT_VERSION(16, Partitioning.TENANT, true),
        /** 順17: {@code t_document_link}（readerは文書のSで保護する）。 */
        DOCUMENT_LINK(17, Partitioning.TENANT, false),
        /** 順18: {@code t_document_disposal_request}（tenant列なし。readerは文書のSで保護する）。 */
        DOCUMENT_DISPOSAL_REQUEST(18, Partitioning.UNPARTITIONED, false);

        private final int planOrder;
        private final Partitioning partitioning;
        private final boolean sharedAllowed;

        Rank(int planOrder, Partitioning partitioning, boolean sharedAllowed) {
            this.planOrder = planOrder;
            this.partitioning = partitioning;
            this.sharedAllowed = sharedAllowed;
        }

        /** 計画 §3.1 の順番号（同じ順13の業務親は宣言順に並ぶ）。 */
        public int planOrder() {
            return planOrder;
        }

        public Partitioning partitioning() {
            return partitioning;
        }

        /** readerが {@link Mode#SHARED} で取ってよい行か。それ以外は {@link Mode#EXCLUSIVE} だけを受け付ける。 */
        public boolean sharedAllowed() {
            return sharedAllowed;
        }
    }

    /** rank・partition・IDの昇順（modeは比較しない）。 */
    public static final Comparator<LockKey> ROW_ORDER = Comparator.comparing(LockKey::rank)
            .thenComparing(LockKey::partition)
            .thenComparingLong(LockKey::id);

    public record LockKey(Rank rank, String partition, long id, Mode mode) {
        public LockKey {
            Objects.requireNonNull(rank, "rank");
            Objects.requireNonNull(mode, "mode");
            partition = partition == null ? "" : partition;
            if (id <= 0) {
                throw new IllegalArgumentException("lock対象IDは正数で指定してください");
            }
            switch (rank.partitioning()) {
                case GLOBAL -> {
                    if (!partition.isEmpty() || id != 1L) {
                        throw new IllegalArgumentException("全体epochのlockはpartitionを空・IDを1で指定してください");
                    }
                }
                case TENANT -> {
                    if (partition.isBlank()) {
                        throw new IllegalArgumentException(rank + "のlockはtenant IDをpartitionに指定してください");
                    }
                }
                case UNPARTITIONED -> {
                    if (!partition.isEmpty()) {
                        throw new IllegalArgumentException(rank + "はtenant列を持たないためpartitionを空で指定してください");
                    }
                }
                default -> throw new IllegalStateException("未知のpartitioning: " + rank.partitioning());
            }
            if (mode == Mode.SHARED && !rank.sharedAllowed()) {
                throw new IllegalArgumentException(rank + "は共有lockを取らない行です（最初からEXCLUSIVEで取ってください）");
            }
        }

        public static LockKey exclusive(Rank rank, String partition, long id) {
            return new LockKey(rank, partition, id, Mode.EXCLUSIVE);
        }

        public static LockKey shared(Rank rank, String partition, long id) {
            return new LockKey(rank, partition, id, Mode.SHARED);
        }

        /** 全体epoch行のlock。 */
        public static LockKey global(Mode mode) {
            return new LockKey(Rank.GLOBAL_AUTHORIZATION_EPOCH, "", 1L, mode);
        }

        /** 同じ行（rank・partition・ID）を指すか（modeは問わない）。 */
        public boolean sameRow(LockKey other) {
            return other != null && rank == other.rank && id == other.id && partition.equals(other.partition);
        }
    }

    public LifecycleLockPlan {
        Objects.requireNonNull(locks, "locks");
        Map<LockKey, Mode> merged = new TreeMap<>(ROW_ORDER);
        for (LockKey key : locks) {
            Objects.requireNonNull(key, "lockKey");
            merged.merge(key, key.mode(), (left, right) ->
                    left == Mode.EXCLUSIVE || right == Mode.EXCLUSIVE ? Mode.EXCLUSIVE : Mode.SHARED);
        }
        List<LockKey> ordered = new ArrayList<>(merged.size());
        merged.forEach((row, mode) -> ordered.add(new LockKey(row.rank(), row.partition(), row.id(), mode)));
        locks = List.copyOf(ordered);
    }

    public static LifecycleLockPlan from(Collection<LockKey> discoveredDependencies) {
        Objects.requireNonNull(discoveredDependencies, "discoveredDependencies");
        return new LifecycleLockPlan(List.copyOf(discoveredDependencies));
    }

    /** 取得済みlock集合（keyとmode）が再discovery結果と一致しない場合はtransaction全体を中止する。 */
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
