package com.ses.featureenablement.state;

import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitchProperties;
import com.ses.config.feature.FeatureSwitches;
import com.ses.entity.FeatureEnablementState;
import com.ses.entity.FeatureSignOff;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import com.ses.mapper.FeatureEnablementGenerationMapper;
import com.ses.mapper.FeatureEnablementSettingMapper;
import com.ses.mapper.FeatureEnablementStateMapper;
import com.ses.mapper.FeatureSignOffBundleMapper;
import com.ses.mapper.FeatureSignOffBundleMemberMapper;
import com.ses.mapper.FeatureSignOffMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FE-A04（FE-AC-006-02・03）: DB の源は snapshot（memory）だけを読み、hot path の判定で DB に往復しない（照会の数を mapper の mock の
 * 呼出し回数で確かめる）。JST 0:00 の境で失効し、要る署名の欠落で閉じ、古さは最後に読めた時刻で判定し（世代の行が無いのは世代 0）、
 * 総スイッチ・{@code unverifiable_since}・この instance の一時の閉鎖で閉じ、DB 管理の印の無い開放単位は property の値を返す。
 */
class DatabaseFeatureEnablementStateSourceTest {

    private static final String TENANT = "default";
    private static final LocalDate SIGNED = LocalDate.of(2026, 11, 1);
    private static final LocalDate EXPIRES = LocalDate.of(2026, 11, 24);

    private final FeatureEnablementGenerationMapper generationMapper = mock(FeatureEnablementGenerationMapper.class);
    private final FeatureEnablementStateMapper stateMapper = mock(FeatureEnablementStateMapper.class);
    private final FeatureEnablementSettingMapper settingMapper = mock(FeatureEnablementSettingMapper.class);
    private final FeatureSignOffMapper signOffMapper = mock(FeatureSignOffMapper.class);
    private final FeatureSignOffBundleMapper bundleMapper = mock(FeatureSignOffBundleMapper.class);
    private final FeatureSignOffBundleMemberMapper memberMapper = mock(FeatureSignOffBundleMemberMapper.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-23T01:00:00Z"));
    private final FeatureEnablementCatalog catalog = FeatureEnablementCatalog.standard();
    private final FeatureEnablementProperties properties = new FeatureEnablementProperties();
    private final FeatureEnablementOverlay overlay = new FeatureEnablementOverlay();
    private FeatureEnablementSnapshotHolder holder;
    private DatabaseFeatureEnablementStateSource source;

    @BeforeEach
    void setUp() {
        properties.setSource(FeatureEnablementProperties.Source.DATABASE);
        FeatureEnablementSnapshotLoader loader = new FeatureEnablementSnapshotLoader(generationMapper, stateMapper,
                settingMapper, signOffMapper, bundleMapper, memberMapper, catalog, properties, clock);
        holder = new FeatureEnablementSnapshotHolder(loader, FeatureEnablementTenant.fixed(TENANT), properties, overlay,
                clock);
        source = new DatabaseFeatureEnablementStateSource(holder, overlay, properties, clock);
        when(generationMapper.selectGeneration(TENANT)).thenReturn(7L);
        when(stateMapper.selectByTenant(TENANT)).thenReturn(List.of(open("NF03")));
        when(signOffMapper.selectConfirmedByTenant(TENANT)).thenReturn(List.of(signOff(1L, "NF03_BUSINESS_OWNER")));
        when(settingMapper.selectByTenant(anyString())).thenReturn(List.of());
        when(bundleMapper.selectConfirmedByTenant(anyString())).thenReturn(List.of());
        when(memberMapper.selectMembersOfConfirmedBundles(anyString())).thenReturn(List.of());
    }

    @Test
    void hot_pathの判定を1万回呼んでもDBへの照会は最初の読込みの1回だけ() {
        assertThat(holder.reload()).isTrue();
        FeatureSwitches switches = new FeatureSwitches(source);

        for (int i = 0; i < 10_000; i++) {
            assertThat(switches.isEnabled(Feature.NF03)).isTrue();
            assertThat(switches.isEnabled(Feature.NF04)).isFalse();
            switches.blockingFeature("/certification-learning-skill-gap");
            switches.disabledKeys();
        }

        verify(generationMapper, times(1)).selectGeneration(TENANT);
        verify(stateMapper, times(1)).selectByTenant(TENANT);
        verify(signOffMapper, times(1)).selectConfirmedByTenant(TENANT);
        verify(settingMapper, times(1)).selectByTenant(TENANT);
        verify(bundleMapper, times(1)).selectConfirmedByTenant(TENANT);
        verify(memberMapper, times(1)).selectMembersOfConfirmedBundles(TENANT);
    }

    @Test
    void 要る署名の失効日のJST0時から閉じる() {
        holder.reload();
        clock.set(Instant.parse("2026-11-23T14:59:59Z"));
        holder.replace(holder.get().verifiedAt(clock.instant()));
        assertThat(source.isOpen(Feature.NF03)).as("2026-11-23T23:59:59+09:00").isTrue();
        clock.set(Instant.parse("2026-11-23T15:00:00Z"));
        holder.replace(holder.get().verifiedAt(clock.instant()));
        assertThat(source.isOpen(Feature.NF03)).as("2026-11-24T00:00:00+09:00").isFalse();
        assertThat(source.closedReasons(Feature.NF03)).containsExactly("signoff.window.closed");
    }

    @Test
    void 要る署名が1つでも無ければopenUntilが閉で開かない() {
        when(signOffMapper.selectConfirmedByTenant(TENANT)).thenReturn(List.of());
        holder.reload();

        assertThat(holder.get().unit(Feature.NF03).openUntil()).isEqualTo(LocalDate.MIN);
        assertThat(source.isOpen(Feature.NF03)).isFalse();
    }

    @Test
    void 古さは最後に読めた時刻で判定し世代が変わらないpollで戻り世代の行が無いのは世代0() {
        holder.reload();
        clock.advance(properties.getMaxStaleness().plusSeconds(1));
        assertThat(source.isOpen(Feature.NF03)).as("古い snapshot は閉").isFalse();
        assertThat(source.closedReasons(Feature.NF03)).contains("snapshot.stale");

        assertThat(holder.poll()).isTrue();
        assertThat(source.isOpen(Feature.NF03)).as("世代が同じでも読めた時刻を更新する").isTrue();
        verify(stateMapper, times(1)).selectByTenant(TENANT);

        when(generationMapper.selectGeneration(TENANT)).thenReturn(null);
        assertThat(holder.poll()).isTrue();
        assertThat(holder.get().generation()).as("世代の行が無いのは世代 0").isZero();

        when(generationMapper.selectGeneration(TENANT)).thenThrow(new IllegalStateException("db"));
        clock.advance(properties.getMaxStaleness().plusSeconds(1));
        assertThat(holder.poll()).isFalse();
        assertThat(source.isOpen(Feature.NF03)).as("読めない間は古さで閉じる（DB の状態は変えない）").isFalse();
    }

    @Test
    void 総スイッチと評価できない状態とこのinstanceの一時の閉鎖で閉じる() {
        holder.reload();
        assertThat(source.isOpen(Feature.NF03)).isTrue();

        overlay.close(Feature.NF03, FeatureEnablementOverlay.AUTO_CLOSE_PENDING);
        assertThat(source.isOpen(Feature.NF03)).isFalse();
        assertThat(source.closedReasons(Feature.NF03)).containsExactly("overlay.AUTO_CLOSE_PENDING");
        overlay.open(Feature.NF03, FeatureEnablementOverlay.AUTO_CLOSE_PENDING);
        assertThat(source.isOpen(Feature.NF03)).isTrue();

        FeatureEnablementState unverifiable = open("NF03");
        unverifiable.setUnverifiableSince(LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault()));
        when(stateMapper.selectByTenant(TENANT)).thenReturn(List.of(unverifiable));
        holder.reload();
        assertThat(source.isOpen(Feature.NF03)).isFalse();
        assertThat(source.closedReasons(Feature.NF03)).containsExactly("evaluation.unverifiable");

        when(stateMapper.selectByTenant(TENANT)).thenReturn(List.of(open("NF03")));
        properties.setAllowed(false);
        holder.reload();
        assertThat(holder.get().masterAllowed()).isFalse();
        assertThat(source.isOpen(Feature.NF03)).isFalse();
        assertThat(source.closedReasons(Feature.NF03)).contains("master.closed");
    }

    @Test
    void 再起動待ちと閉鎖と行の無い開放単位は全て閉を返す() {
        FeatureEnablementState manuallyClosed = open("NF03");
        manuallyClosed.setState("MANUALLY_CLOSED");
        manuallyClosed.setClosedReason("MANUAL_OTHER");
        FeatureEnablementState pendingRestart = open("NF05_INBOUND");
        pendingRestart.setState("OPEN_PENDING_RESTART");
        when(stateMapper.selectByTenant(TENANT)).thenReturn(List.of(manuallyClosed, pendingRestart));
        holder.reload();
        assertThat(source.isOpen(Feature.NF03)).isFalse();
        assertThat(source.closedReasons(Feature.NF03)).containsExactly("state.MANUALLY_CLOSED(MANUAL_OTHER)");
        assertThat(source.isOpen(Feature.NF05_INBOUND)).isFalse();
        assertThat(source.closedReasons(Feature.NF05_INBOUND)).containsExactly("state.OPEN_PENDING_RESTART");

        // 行の無い開放単位は全て CLOSED（FE-E02 で property への fallback は無い）
        assertThat(source.isOpen(Feature.NF01)).isFalse();
        assertThat(source.isOpen(Feature.NF04)).isFalse();
        assertThat(source.closedReasons(Feature.NF04)).containsExactly("state.CLOSED");
    }

    @Test
    void 一度も読めていなければ全て閉() {
        when(generationMapper.selectGeneration(TENANT)).thenThrow(new IllegalStateException("db"));
        assertThat(holder.reload()).isFalse();
        assertThat(holder.loaded()).isFalse();
        assertThat(source.isOpen(Feature.NF03)).isFalse();
        assertThat(source.closedReasons(Feature.NF03)).contains("snapshot.stale");
    }

    // ---------------------------------------------------------------- helper

    private static FeatureEnablementState open(String feature) {
        FeatureEnablementState state = new FeatureEnablementState();
        state.setTenantId(TENANT);
        state.setFeatureKey(feature);
        state.setState("OPEN");
        state.setVersion(1);
        state.setOpenRequestId(1L);
        state.setOpenedBy(10L);
        state.setApprovedBy(20L);
        return state;
    }

    private static FeatureSignOff signOff(long id, String kind) {
        FeatureSignOff signOff = new FeatureSignOff();
        signOff.setId(id);
        signOff.setVersion(0);
        signOff.setKindCode(kind);
        signOff.setReference("REF-" + id);
        signOff.setSignerRole("BUSINESS_OWNER");
        signOff.setSignedOn(SIGNED);
        signOff.setExpiresOn(EXPIRES);
        signOff.setKindClass("DECISION");
        return signOff;
    }

    /** 試験の時計（進められる）。 */
    static final class MutableClock extends Clock {
        private Instant instant;
        private final List<Instant> history = new ArrayList<>();

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant value) {
            history.add(instant);
            instant = value;
        }

        void advance(Duration duration) {
            set(instant.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            MutableClock outer = this;
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return zone;
                }

                @Override
                public Clock withZone(ZoneId other) {
                    return outer.withZone(other);
                }

                @Override
                public Instant instant() {
                    return outer.instant();
                }
            };
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
