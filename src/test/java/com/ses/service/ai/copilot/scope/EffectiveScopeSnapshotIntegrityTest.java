package com.ses.service.ai.copilot.scope;

import com.ses.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** NF08: caller supplied canonicalMembers/scopeHashの不整合はsnapshotとして扱わない。 */
class EffectiveScopeSnapshotIntegrityTest {

    @Test
    void canonicalMembersとscopeHashの不一致はfailClosedする() {
        BusinessException ex = assertThrows(BusinessException.class, () -> new EffectiveScopeSnapshot(
                "tenant-a", 1L, LocalDate.of(2026, 9, 8), "COMPANY_WIDE",
                true, false, false,
                null, null, null, null, null, null, null, null, null,
                EffectiveScopeSnapshotFactory.POLICY_VERSION, false, "ALL", "caller-supplied-hash"));

        assertEquals("EFFECTIVE_SCOPE_SNAPSHOT_INVALID", ex.getMessage());
    }
}
