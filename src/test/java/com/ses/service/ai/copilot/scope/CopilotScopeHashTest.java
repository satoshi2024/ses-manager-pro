package com.ses.service.ai.copilot.scope;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CopilotScopeHashTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 7);

    @Test
    void 集合12と34は異なるhashになる() {
        String hash12 = hashWith(Set.of(1L, 2L), Set.of(), Set.of(), Set.of(), Set.of());
        String hash34 = hashWith(Set.of(), Set.of(3L, 4L), Set.of(), Set.of(), Set.of());
        assertNotEquals(hash12, hash34);
    }

    @Test
    void 順序21と12は同一hashになる() {
        String hash12 = hashWith(Set.of(), Set.of(1L, 2L), Set.of(), Set.of(), Set.of());
        String hash21 = hashWith(Set.of(), Set.of(2L, 1L), Set.of(), Set.of(), Set.of());
        assertEquals(hash12, hash21);
    }

    @Test
    void 同一IDが異なる次元ではhashが異なる() {
        String customer = hashWith(Set.of(1L), Set.of(), Set.of(), Set.of(), Set.of());
        String contract = hashWith(Set.of(), Set.of(1L), Set.of(), Set.of(), Set.of());
        assertNotEquals(customer, contract);
    }

    @Test
    void tenant変更でhashが変わる() {
        String defaultTenant = CopilotScopeHash.hash(
                "default", "", "SALES_DATA_SCOPED", CopilotScopeResolver.POLICY_VERSION, AS_OF,
                Set.of(1L), Set.of(), Set.of(), Set.of(), Set.of());
        String otherTenant = CopilotScopeHash.hash(
                "tenant-b", "", "SALES_DATA_SCOPED", CopilotScopeResolver.POLICY_VERSION, AS_OF,
                Set.of(1L), Set.of(), Set.of(), Set.of(), Set.of());
        assertNotEquals(defaultTenant, otherTenant);
    }

    @Test
    void legalEntity変更でhashが変わる() {
        String without = CopilotScopeHash.hash(
                "default", "", "COMPANY_WIDE", CopilotScopeResolver.POLICY_VERSION, AS_OF,
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
        String withEntity = CopilotScopeHash.hash(
                "default", "42", "COMPANY_WIDE", CopilotScopeResolver.POLICY_VERSION, AS_OF,
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
        assertNotEquals(without, withEntity);
    }

    @Test
    void asOf変更でhashが変わる() {
        String day1 = hashAt(LocalDate.of(2026, 9, 7));
        String day2 = hashAt(LocalDate.of(2026, 9, 8));
        assertNotEquals(day1, day2);
    }

    @Test
    void canonical入力は命名次元と辞書順キーを含む() {
        String canonical = CopilotScopeHash.canonicalInput(
                "default",
                "9",
                "SALES_DATA_SCOPED",
                CopilotScopeResolver.POLICY_VERSION,
                AS_OF,
                Set.of(2L, 1L),
                Set.of(4L, 3L),
                Set.of(),
                Set.of(),
                Set.of());
        assertEquals("""
                asOf=2026-09-07
                contractIds=3,4
                customerIds=1,2
                directUserIds=
                engineerIds=
                legalEntity=9
                organizationIds=
                policyVersion=nf08-effective-scope-1
                scopeType=SALES_DATA_SCOPED
                tenant=default
                """, canonical);
    }

    private static String hashWith(
            Set<Long> customerIds,
            Set<Long> contractIds,
            Set<Long> engineerIds,
            Set<Long> organizationIds,
            Set<Long> directUserIds) {
        return CopilotScopeHash.hash(
                "default",
                "",
                "SALES_DATA_SCOPED",
                CopilotScopeResolver.POLICY_VERSION,
                AS_OF,
                customerIds,
                contractIds,
                engineerIds,
                organizationIds,
                directUserIds);
    }

    private static String hashAt(LocalDate asOf) {
        return CopilotScopeHash.hash(
                "default",
                "",
                "COMPANY_WIDE",
                CopilotScopeResolver.POLICY_VERSION,
                asOf,
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of());
    }
}
