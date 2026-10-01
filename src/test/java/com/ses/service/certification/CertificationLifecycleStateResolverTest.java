package com.ses.service.certification;

import com.ses.entity.LifecycleCase;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CertificationLifecycleStateResolverTest {

    private final CertificationLifecycleStateResolver resolver = new CertificationLifecycleStateResolver();
    private final LocalDate asOf = LocalDate.of(2026, 8, 28);

    @Test
    void futureの退職と休職は現在の状態を変えない() {
        LifecycleCase futureLeave = lifecycle(1L, "LEAVE", "ACTIVE", asOf.plusDays(1), null);
        LifecycleCase futureResignation = lifecycle(2L, "RESIGNATION", "COMPLETED", asOf.plusDays(2),
                asOf.plusDays(2).atStartOfDay());

        CertificationLifecycleStateResolver.Resolution result = resolver.resolve(
                List.of(futureLeave, futureResignation), asOf);

        assertEquals("ACTIVE", result.state());
        assertFalse(result.reinstatement());
    }

    @Test
    void 休職から復職後のasOfはactiveになる() {
        LifecycleCase leave = lifecycle(1L, "LEAVE", "COMPLETED", asOf.minusDays(10),
                asOf.minusDays(3).atStartOfDay());
        LifecycleCase reinstatement = lifecycle(2L, "REINSTATEMENT", "COMPLETED", asOf.minusDays(2),
                asOf.minusDays(2).atStartOfDay());

        CertificationLifecycleStateResolver.Resolution result = resolver.resolve(List.of(leave, reinstatement), asOf);

        assertEquals("ACTIVE", result.state());
        assertEquals(true, result.reinstatement());
    }

    private LifecycleCase lifecycle(Long id, String type, String status, LocalDate anchor, LocalDateTime completedAt) {
        LifecycleCase item = new LifecycleCase();
        item.setId(id);
        item.setLifecycleType(type);
        item.setStatus(status);
        item.setAnchorDate(anchor);
        item.setCompletedAt(completedAt);
        return item;
    }
}
