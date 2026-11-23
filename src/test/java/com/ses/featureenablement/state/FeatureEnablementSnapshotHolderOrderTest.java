package com.ses.featureenablement.state;

import com.ses.featureenablement.config.FeatureEnablementProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * review rb-fe-a1 P2-3: poll の読込みが commit の前に始まり、commit の後の読直しより後に終わっても、旧い snapshot で新しい snapshot を
 * 上書きしない（読み始めの順で比べる）。DB の世代が小さくなる場合（PITR の後・試験の rollback）は、後に読み始めた snapshot が勝つ。
 */
class FeatureEnablementSnapshotHolderOrderTest {

    private static final Instant NOW = Instant.parse("2026-11-23T01:00:00Z");

    @Test
    void 先に読み始めて後に終わった旧い世代のsnapshotは新しいsnapshotを上書きしない() throws Exception {
        FeatureEnablementSnapshotLoader loader = mock(FeatureEnablementSnapshotLoader.class);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        when(loader.load("default"))
                .thenAnswer(invocation -> {
                    // poll の読込み: 世代 5 を読んだ所で止まる（commit の前に読んだ旧い状態）
                    firstEntered.countDown();
                    assertThat(releaseFirst.await(30, TimeUnit.SECONDS)).isTrue();
                    return snapshot(5);
                })
                .thenReturn(snapshot(6));
        FeatureEnablementSnapshotHolder holder = holder(loader);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> poll = pool.submit(holder::reload);
            assertThat(firstEntered.await(30, TimeUnit.SECONDS)).isTrue();

            // commit の後の読直し（後に読み始め、先に終わる）
            assertThat(holder.reload()).isTrue();
            assertThat(holder.get().generation()).isEqualTo(6L);

            releaseFirst.countDown();
            assertThat(poll.get(30, TimeUnit.SECONDS)).as("読めたことは返す").isTrue();
            assertThat(holder.get().generation()).as("旧い世代へ戻さない").isEqualTo(6L);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void 後に読み始めたsnapshotは世代が小さくても差し替える() {
        FeatureEnablementSnapshotLoader loader = mock(FeatureEnablementSnapshotLoader.class);
        when(loader.load("default")).thenReturn(snapshot(9)).thenReturn(snapshot(2));
        FeatureEnablementSnapshotHolder holder = holder(loader);

        holder.reload();
        holder.reload();

        assertThat(holder.get().generation()).as("PITR の後・試験の rollback で世代が戻った DB を読み直せる").isEqualTo(2L);
    }

    private static FeatureEnablementSnapshotHolder holder(FeatureEnablementSnapshotLoader loader) {
        FeatureEnablementProperties properties = new FeatureEnablementProperties();
        properties.setSource(FeatureEnablementProperties.Source.DATABASE);
        return new FeatureEnablementSnapshotHolder(loader, FeatureEnablementTenant.fixed("default"), properties,
                new FeatureEnablementOverlay(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static FeatureEnablementSnapshot snapshot(long generation) {
        return new FeatureEnablementSnapshot(generation, NOW, NOW, true, Map.of(), Map.of(), Map.of(), Map.of());
    }
}
