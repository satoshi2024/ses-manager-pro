package com.ses.featureenablement.state;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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

    /**
     * review rb-fe-a1 1a4fd48d 差分 P3-N2: commit の後の読直し（後に読み始めた）が失敗した時、commit の前に読み始めた poll の読込みが
     * 後で成功しても差し替えず、変更した開放単位の一時の閉鎖を外さない（失敗した読込みが障壁になる）。その後に読み始めた読込みは
     * 差し替え、一時の閉鎖を外す。
     */
    @Test
    void commitの後の読直しが失敗すれば先に読み始めた読込みは後で成功しても差し替えず一時の閉鎖を外さない() throws Exception {
        FeatureEnablementSnapshotLoader loader = mock(FeatureEnablementSnapshotLoader.class);
        FeatureEnablementSnapshot initial = snapshot(4);
        CountDownLatch pollEntered = new CountDownLatch(1);
        CountDownLatch releasePoll = new CountDownLatch(1);
        when(loader.load("default"))
                .thenReturn(initial)
                .thenAnswer(invocation -> {
                    // poll の読込み: commit の前に読み始め、commit の前の状態を読んだ所で止まる
                    pollEntered.countDown();
                    assertThat(releasePoll.await(30, TimeUnit.SECONDS)).isTrue();
                    return snapshot(4);
                })
                .thenThrow(new IllegalStateException("DB に届かない"))
                .thenReturn(snapshot(5));
        FeatureEnablementOverlay overlay = new FeatureEnablementOverlay();
        FeatureEnablementSnapshotHolder holder = holder(loader, overlay);
        assertThat(holder.reload()).isTrue();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> poll = pool.submit(holder::reload);
            assertThat(pollEntered.await(30, TimeUnit.SECONDS)).isTrue();

            // commit の後の読直し（transaction の外なので直ちに読む）が失敗する
            holder.reloadAfterCommit(List.of(Feature.NF04));
            assertThat(overlay.has(Feature.NF04, FeatureEnablementOverlay.REFRESH_PENDING)).isTrue();

            releasePoll.countDown();
            assertThat(poll.get(30, TimeUnit.SECONDS)).as("読めたことは返す").isTrue();
            assertThat(holder.get()).as("障壁より前に読み始めた読込みは差し替えない").isSameAs(initial);
            assertThat(overlay.has(Feature.NF04, FeatureEnablementOverlay.REFRESH_PENDING))
                    .as("commit を含まないかもしれない読込みで一時の閉鎖を外さない（fail-open の窓を作らない）").isTrue();

            assertThat(holder.reload()).isTrue();
            assertThat(holder.get().generation()).isEqualTo(5L);
            assertThat(overlay.has(Feature.NF04, FeatureEnablementOverlay.REFRESH_PENDING))
                    .as("障壁より後に読み始めた読込みは commit を含むため外す").isFalse();
        } finally {
            releasePoll.countDown();
            pool.shutdownNow();
        }
    }

    /** P3-N2: 失敗した読直しより後に読み始めた読込みが先に差し替えていれば、その読込みが commit を含むため一時の閉鎖を付けない。 */
    @Test
    void 後に読み始めた読込みが先に差し替えていれば失敗した読直しは一時の閉鎖を付けない() throws Exception {
        FeatureEnablementSnapshotLoader loader = mock(FeatureEnablementSnapshotLoader.class);
        CountDownLatch retryEntered = new CountDownLatch(1);
        CountDownLatch releaseRetry = new CountDownLatch(1);
        when(loader.load("default"))
                .thenAnswer(invocation -> {
                    // commit の後の読直し: 読み始めた所で止まり、後で失敗する
                    retryEntered.countDown();
                    assertThat(releaseRetry.await(30, TimeUnit.SECONDS)).isTrue();
                    throw new IllegalStateException("DB に届かない");
                })
                .thenReturn(snapshot(7));
        FeatureEnablementOverlay overlay = new FeatureEnablementOverlay();
        FeatureEnablementSnapshotHolder holder = holder(loader, overlay);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> retry = pool.submit(() -> holder.reloadAfterCommit(List.of(Feature.NF04)));
            assertThat(retryEntered.await(30, TimeUnit.SECONDS)).isTrue();

            assertThat(holder.reload()).isTrue();
            releaseRetry.countDown();
            retry.get(30, TimeUnit.SECONDS);

            assertThat(holder.get().generation()).isEqualTo(7L);
            assertThat(overlay.has(Feature.NF04, FeatureEnablementOverlay.REFRESH_PENDING)).isFalse();
        } finally {
            releaseRetry.countDown();
            pool.shutdownNow();
        }
    }

    private static FeatureEnablementSnapshotHolder holder(FeatureEnablementSnapshotLoader loader) {
        return holder(loader, new FeatureEnablementOverlay());
    }

    private static FeatureEnablementSnapshotHolder holder(FeatureEnablementSnapshotLoader loader,
                                                          FeatureEnablementOverlay overlay) {
        FeatureEnablementProperties properties = new FeatureEnablementProperties();
        properties.setSource(FeatureEnablementProperties.Source.DATABASE);
        return new FeatureEnablementSnapshotHolder(loader, FeatureEnablementTenant.fixed("default"), properties,
                overlay, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static FeatureEnablementSnapshot snapshot(long generation) {
        return new FeatureEnablementSnapshot(generation, NOW, NOW, true, Map.of(), Map.of(), Map.of(), Map.of());
    }
}
