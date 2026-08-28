package com.ses.service.pwa;

import com.ses.mapper.PwaClientMutationMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PwaClientMutationCleanupSchedulerTest {

    @Test
    void 古いledgerだけを削除し削除件数をmetricへ記録する() {
        PwaClientMutationMapper mapper = mock(PwaClientMutationMapper.class);
        when(mapper.deleteOlderThan(any(LocalDateTime.class))).thenReturn(3);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Clock clock = Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneId.of("Asia/Tokyo"));
        PwaClientMutationCleanupScheduler scheduler =
                new PwaClientMutationCleanupScheduler(mapper, clock, registry, 30);

        scheduler.cleanupExpired();

        verify(mapper).deleteOlderThan(LocalDateTime.of(2026, 7, 29, 9, 0));
        assertThat(registry.get("ses.pwa.mutation.cleanup").counter().count()).isEqualTo(3);
    }
}
