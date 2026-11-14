package com.ses.service.lifecycle.evidence;

import com.ses.service.lifecycle.evidence.LifecycleLockPlan.LockKey;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan.Mode;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan.Rank;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U1（NF01細粒度epoch計画 §3.1・§3.3・§6.2）: lock順のrank定義、同一行のmode統合、partition順、依存差分の検出。
 */
class LifecycleLockPlanTest {

    @Test
    void rankの宣言順は計画の順番号どおりで承認申請がsys_userより前にありtenant_epochとactor_epochを持たない() {
        List<Rank> ranks = Arrays.asList(Rank.values());
        assertEquals(List.of(
                Rank.GLOBAL_AUTHORIZATION_EPOCH, Rank.APPROVAL_REQUEST, Rank.SYS_USER, Rank.LIFECYCLE_CASE,
                Rank.LIFECYCLE_TASK, Rank.ORGANIZATION, Rank.ENGINEER, Rank.USER_ORGANIZATION,
                Rank.ENGINEER_ACCOUNT_LINK, Rank.ENGINEER_SALES, Rank.ENGINEER_ACCOUNTING_HISTORY,
                Rank.PORTAL_ENGINEER_LINK, Rank.CUSTOMER, Rank.CONTRACT, Rank.PROPOSAL, Rank.PROJECT,
                Rank.SALES_ACTIVITY, Rank.ASSET, Rank.ASSET_ASSIGNMENT, Rank.ACCEPTANCE, Rank.SERVICE_REQUEST,
                Rank.EXTERNAL_ACCOUNT_REFERENCE, Rank.DOCUMENT, Rank.DOCUMENT_VERSION, Rank.DOCUMENT_LINK,
                Rank.DOCUMENT_DISPOSAL_REQUEST), ranks);
        for (int i = 1; i < ranks.size(); i++) {
            assertTrue(ranks.get(i - 1).planOrder() <= ranks.get(i).planOrder(),
                    "宣言順と計画の順番号が一致すること: " + ranks.get(i - 1) + " -> " + ranks.get(i));
        }
        assertEquals(2, Rank.APPROVAL_REQUEST.planOrder());
        assertEquals(3, Rank.SYS_USER.planOrder());
        assertEquals(12, Rank.PORTAL_ENGINEER_LINK.planOrder());
        assertEquals(14, Rank.EXTERNAL_ACCOUNT_REFERENCE.planOrder());
        assertEquals(18, Rank.DOCUMENT_DISPOSAL_REQUEST.planOrder());
        assertTrue(ranks.stream().noneMatch(rank -> rank.name().startsWith("TENANT_")
                || rank.name().equals("ACTOR_AUTHORIZATION_EPOCH")), "tenant epoch・actor epoch表のrankは廃止");
    }

    @Test
    void 共有lockはreaderがSを取る行だけに許し他の行はEXCLUSIVEだけを受け付ける() {
        for (Rank rank : List.of(Rank.GLOBAL_AUTHORIZATION_EPOCH, Rank.SYS_USER, Rank.ORGANIZATION, Rank.ENGINEER,
                Rank.DOCUMENT, Rank.DOCUMENT_VERSION)) {
            assertTrue(rank.sharedAllowed(), rank.name());
        }
        assertThrows(IllegalArgumentException.class, () -> LockKey.shared(Rank.LIFECYCLE_CASE, "tenant-a", 1));
        assertThrows(IllegalArgumentException.class, () -> LockKey.shared(Rank.LIFECYCLE_TASK, "tenant-a", 1));
        assertThrows(IllegalArgumentException.class, () -> LockKey.shared(Rank.APPROVAL_REQUEST, "tenant-a", 1));
        assertThrows(IllegalArgumentException.class, () -> LockKey.shared(Rank.ENGINEER_SALES, "", 1));
        assertThrows(IllegalArgumentException.class, () -> LockKey.shared(Rank.DOCUMENT_LINK, "tenant-a", 1));
    }

    @Test
    void partitionは全体と組織とtenant列の無い行では空でtenant行では必須になる() {
        assertEquals(LockKey.global(Mode.SHARED), new LockKey(Rank.GLOBAL_AUTHORIZATION_EPOCH, null, 1, Mode.SHARED));
        assertThrows(IllegalArgumentException.class,
                () -> LockKey.exclusive(Rank.GLOBAL_AUTHORIZATION_EPOCH, "", 2));
        assertThrows(IllegalArgumentException.class,
                () -> LockKey.exclusive(Rank.GLOBAL_AUTHORIZATION_EPOCH, "tenant-a", 1));
        assertThrows(IllegalArgumentException.class, () -> LockKey.shared(Rank.ORGANIZATION, "tenant-a", 5));
        assertThrows(IllegalArgumentException.class, () -> LockKey.exclusive(Rank.ENGINEER_SALES, "tenant-a", 5));
        assertThrows(IllegalArgumentException.class, () -> LockKey.exclusive(Rank.ENGINEER, "", 5));
        assertThrows(IllegalArgumentException.class, () -> LockKey.exclusive(Rank.SYS_USER, " ", 5));
        assertThrows(IllegalArgumentException.class, () -> LockKey.exclusive(Rank.ENGINEER, "tenant-a", 0));
    }

    @Test
    void 同じ行のSHAREDとEXCLUSIVEはEXCLUSIVEへ畳まれ並びはrankとpartitionとIDの昇順になる() {
        LifecycleLockPlan plan = LifecycleLockPlan.from(List.of(
                LockKey.shared(Rank.DOCUMENT, "tenant-b", 3),
                LockKey.shared(Rank.ENGINEER, "tenant-a", 9),
                LockKey.exclusive(Rank.LIFECYCLE_TASK, "tenant-a", 21),
                LockKey.exclusive(Rank.ENGINEER, "tenant-a", 9),
                LockKey.shared(Rank.ENGINEER, "tenant-a", 9),
                LockKey.shared(Rank.ORGANIZATION, "", 40),
                LockKey.shared(Rank.ORGANIZATION, "", 7),
                LockKey.exclusive(Rank.LIFECYCLE_TASK, "tenant-a", 20),
                LockKey.exclusive(Rank.LIFECYCLE_CASE, "tenant-a", 5),
                LockKey.shared(Rank.DOCUMENT, "tenant-a", 30),
                LockKey.shared(Rank.SYS_USER, "tenant-a", 2),
                LockKey.exclusive(Rank.APPROVAL_REQUEST, "tenant-a", 99),
                LockKey.global(Mode.SHARED)));

        assertEquals(List.of(
                LockKey.global(Mode.SHARED),
                LockKey.exclusive(Rank.APPROVAL_REQUEST, "tenant-a", 99),
                LockKey.shared(Rank.SYS_USER, "tenant-a", 2),
                LockKey.exclusive(Rank.LIFECYCLE_CASE, "tenant-a", 5),
                LockKey.exclusive(Rank.LIFECYCLE_TASK, "tenant-a", 20),
                LockKey.exclusive(Rank.LIFECYCLE_TASK, "tenant-a", 21),
                LockKey.shared(Rank.ORGANIZATION, "", 7),
                LockKey.shared(Rank.ORGANIZATION, "", 40),
                LockKey.exclusive(Rank.ENGINEER, "tenant-a", 9),
                LockKey.shared(Rank.DOCUMENT, "tenant-a", 30),
                LockKey.shared(Rank.DOCUMENT, "tenant-b", 3)), plan.locks());
        assertTrue(plan.locks().get(8).sameRow(LockKey.shared(Rank.ENGINEER, "tenant-a", 9)));
        assertFalse(plan.locks().get(8).sameRow(LockKey.shared(Rank.ENGINEER, "tenant-b", 9)));
    }

    @Test
    void 依存先の増減とmodeの変化とpartitionの変化でrequireSameDependenciesが失敗する() {
        List<LockKey> base = List.of(LockKey.global(Mode.SHARED), LockKey.shared(Rank.SYS_USER, "tenant-a", 2),
                LockKey.exclusive(Rank.LIFECYCLE_CASE, "tenant-a", 5));
        LifecycleLockPlan selected = LifecycleLockPlan.from(base);

        selected.requireSameDependencies(LifecycleLockPlan.from(List.of(base.get(2), base.get(0), base.get(1), base.get(1))));
        assertThrows(LifecycleLockPlan.DependencyChangedException.class,
                () -> selected.requireSameDependencies(LifecycleLockPlan.from(base.subList(0, 2))));
        assertThrows(LifecycleLockPlan.DependencyChangedException.class,
                () -> selected.requireSameDependencies(LifecycleLockPlan.from(List.of(base.get(0), base.get(1), base.get(2),
                        LockKey.exclusive(Rank.LIFECYCLE_TASK, "tenant-a", 6)))));
        assertThrows(LifecycleLockPlan.DependencyChangedException.class,
                () -> selected.requireSameDependencies(LifecycleLockPlan.from(List.of(LockKey.global(Mode.EXCLUSIVE),
                        base.get(1), base.get(2)))));
        assertThrows(LifecycleLockPlan.DependencyChangedException.class,
                () -> selected.requireSameDependencies(LifecycleLockPlan.from(List.of(base.get(0),
                        LockKey.shared(Rank.SYS_USER, "tenant-b", 2), base.get(2)))));
        // 同じ行の昇格は計画構築時に畳まれるため、SHAREDだけの集合とSHARED+EXCLUSIVEの集合は別物として検出される
        assertThrows(LifecycleLockPlan.DependencyChangedException.class,
                () -> selected.requireSameDependencies(LifecycleLockPlan.from(List.of(base.get(0), base.get(1), base.get(2),
                        LockKey.exclusive(Rank.SYS_USER, "tenant-a", 2)))));
    }
}
