package com.ses.service.security.epoch;

import com.ses.service.lifecycle.evidence.LifecycleLockPlan;

import java.util.Comparator;
import java.util.Objects;

/**
 * NF01 細粒度認可epochのlock anchor（計画 §2.2）。値の等価性で同一transaction内の重複増分を除く（E-4）。
 *
 * <p>tenantを持つanchorは {@code id + tenant_id} で照合する。組織はtenantに依存しない（数値 {@code tenant_id} を
 * 文字列tenantへ変換しない、計画 §2.6）。タスク世代は所属案件のtenantで照合する。</p>
 */
public sealed interface AuthorizationEpochAnchor {

    /** 複数anchorを進める場合の順序。lock順（計画 §3.1）のrank、partition、IDの昇順。 */
    Comparator<AuthorizationEpochAnchor> LOCK_ORDER = Comparator
            .comparing(AuthorizationEpochAnchor::rank)
            .thenComparing(AuthorizationEpochAnchor::partition)
            .thenComparingLong(AuthorizationEpochAnchor::id);

    /** このanchor行が属するlock rank。 */
    LifecycleLockPlan.Rank rank();

    /** lock順のpartition（tenant行はtenant ID、全体と組織は空文字）。 */
    String partition();

    /** anchor行のID（全体は1、タスク世代はタスクID）。 */
    long id();

    /** 全体epoch（role menuの集合変化・認可configキーの値変化。Q-3）。 */
    static Global global() {
        return Global.INSTANCE;
    }

    static Actor actor(String tenantId, long userId) {
        return new Actor(tenantId, userId);
    }

    static Engineer engineer(String tenantId, long engineerId) {
        return new Engineer(tenantId, engineerId);
    }

    static Organization organization(long organizationId) {
        return new Organization(organizationId);
    }

    static Document document(String tenantId, long documentId) {
        return new Document(tenantId, documentId);
    }

    static TaskGeneration taskGeneration(String tenantId, long caseId, long taskId) {
        return new TaskGeneration(tenantId, caseId, taskId);
    }

    /** {@code t_authorization_global_epoch(singleton_id = 1)}。 */
    record Global() implements AuthorizationEpochAnchor {
        static final Global INSTANCE = new Global();

        @Override
        public LifecycleLockPlan.Rank rank() {
            return LifecycleLockPlan.Rank.GLOBAL_AUTHORIZATION_EPOCH;
        }

        @Override
        public String partition() {
            return "";
        }

        @Override
        public long id() {
            return 1L;
        }
    }

    /** {@code sys_user.actor_authz_epoch}。 */
    record Actor(String tenantId, long userId) implements AuthorizationEpochAnchor {
        public Actor {
            tenantId = requireTenant(tenantId);
            requirePositive(userId, "userId");
        }

        @Override
        public LifecycleLockPlan.Rank rank() {
            return LifecycleLockPlan.Rank.SYS_USER;
        }

        @Override
        public String partition() {
            return tenantId;
        }

        @Override
        public long id() {
            return userId;
        }
    }

    /** {@code t_engineer.engineer_scope_epoch}。 */
    record Engineer(String tenantId, long engineerId) implements AuthorizationEpochAnchor {
        public Engineer {
            tenantId = requireTenant(tenantId);
            requirePositive(engineerId, "engineerId");
        }

        @Override
        public LifecycleLockPlan.Rank rank() {
            return LifecycleLockPlan.Rank.ENGINEER;
        }

        @Override
        public String partition() {
            return tenantId;
        }

        @Override
        public long id() {
            return engineerId;
        }
    }

    /** {@code m_organization_unit.org_scope_epoch}（部分木の世代。writerは旧・新の祖先鎖をID昇順で進める）。 */
    record Organization(long organizationId) implements AuthorizationEpochAnchor {
        public Organization {
            requirePositive(organizationId, "organizationId");
        }

        @Override
        public LifecycleLockPlan.Rank rank() {
            return LifecycleLockPlan.Rank.ORGANIZATION;
        }

        @Override
        public String partition() {
            return "";
        }

        @Override
        public long id() {
            return organizationId;
        }
    }

    /** {@code t_document.evidence_eligibility_epoch}。 */
    record Document(String tenantId, long documentId) implements AuthorizationEpochAnchor {
        public Document {
            tenantId = requireTenant(tenantId);
            requirePositive(documentId, "documentId");
        }

        @Override
        public LifecycleLockPlan.Rank rank() {
            return LifecycleLockPlan.Rank.DOCUMENT;
        }

        @Override
        public String partition() {
            return tenantId;
        }

        @Override
        public long id() {
            return documentId;
        }
    }

    /** {@code t_lifecycle_task.task_generation}（所属案件をX lock済みであること）。 */
    record TaskGeneration(String tenantId, long caseId, long taskId) implements AuthorizationEpochAnchor {
        public TaskGeneration {
            tenantId = requireTenant(tenantId);
            requirePositive(caseId, "caseId");
            requirePositive(taskId, "taskId");
        }

        @Override
        public LifecycleLockPlan.Rank rank() {
            return LifecycleLockPlan.Rank.LIFECYCLE_TASK;
        }

        @Override
        public String partition() {
            return tenantId;
        }

        @Override
        public long id() {
            return taskId;
        }
    }

    private static String requireTenant(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantIdは必須です");
        }
        return tenantId;
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + "は正数で指定してください");
        }
    }
}
