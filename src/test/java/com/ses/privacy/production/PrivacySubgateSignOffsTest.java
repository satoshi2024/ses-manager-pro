package com.ses.privacy.production;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.FeatureEnablementTestSnapshots;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import com.ses.featureenablement.evaluation.SubgateMonitor;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import com.ses.featureenablement.state.FeatureEnablementSnapshotHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * review rb-fe-c P2-1（requirements §6.11）: NF07 を DB の開放状態で管理する間、副入口（hold 開始・REL・REG）の入口の gate は目録の
 * 署名（証拠の類 {@code NF07_D05_OWNER_ACCEPTANCE}・{@code NF07_TRUST_KMS}・{@code NF07_TRUST_STAGING} を含む）を要求ごとに読み、取消し・
 * 失効なら副入口だけを閉じる（保持・保全の開放単位は閉じない）。新しい署名の確認の後は自動で再開する（REL・REG は 2026-11-23 の利用者の確認、hold 開始は J-03 の推奨）。
 */
class PrivacySubgateSignOffsTest {

    private static final Instant NOW = Instant.parse("2026-11-23T01:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 23);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Asia/Tokyo"));

    private final FeatureEnablementCatalog catalog = FeatureEnablementCatalog.standard();

    @Test
    void 証拠の類の署名が取消し失効なら該当の副入口だけが閉じ確認し直せば戻る() {
        AtomicReference<FeatureEnablementSnapshot> current = new AtomicReference<>(snapshot(null));
        PrivacySubgateSignOffs signOffs = signOffs(databaseSource(), current);

        assertThat(signOffs.valid(PrivacySubgateSignOffs.HOLD_START)).isTrue();
        assertThat(signOffs.valid(PrivacySubgateSignOffs.RELEASE)).isTrue();

        // 受理（NF07_D05_OWNER_ACCEPTANCE）の取消し（確認済みの署名が無くなる）: hold 開始だけが閉じる
        current.set(snapshot("NF07_D05_OWNER_ACCEPTANCE"));
        assertThat(signOffs.valid(PrivacySubgateSignOffs.HOLD_START)).isFalse();
        assertThat(signOffs.valid(PrivacySubgateSignOffs.RELEASE)).isTrue();
        assertThat(current.get().unit(Feature.NF07_RETENTION).state().openLike()).as("保持・保全は開いたまま").isTrue();

        // KMS の trust 文書の確認（NF07_TRUST_KMS）の取消し: REL だけが閉じる
        current.set(snapshot("NF07_TRUST_KMS"));
        assertThat(signOffs.valid(PrivacySubgateSignOffs.RELEASE)).isFalse();
        assertThat(signOffs.valid(PrivacySubgateSignOffs.HOLD_START)).isTrue();

        // staging の確認（NF07_TRUST_STAGING）の失効（失効日の当日）: REL が閉じる
        current.set(FeatureEnablementTestSnapshots.builder(NOW).open(Feature.NF07_RETENTION)
                .signOff("NF07_D05", 1, 0, TODAY.minusDays(10), TODAY.plusDays(300))
                .signOff("NF07_D05_OWNER_ACCEPTANCE", 2, 0, TODAY.minusDays(10), TODAY.plusDays(300))
                .signOff("NF07_D03", 3, 0, TODAY.minusDays(10), TODAY.plusDays(300))
                .signOff("NF07_TRUST_KMS", 4, 0, TODAY.minusDays(10), TODAY.plusDays(300))
                .signOff("NF07_TRUST_STAGING", 5, 0, TODAY.minusDays(10), TODAY).build());
        assertThat(signOffs.valid(PrivacySubgateSignOffs.RELEASE)).isFalse();

        // 新しい署名を二者で確認した後（snapshot に戻る）は自動で開く
        current.set(snapshot(null));
        assertThat(signOffs.valid(PrivacySubgateSignOffs.RELEASE)).isTrue();
        assertThat(signOffs.valid(PrivacySubgateSignOffs.HOLD_START)).isTrue();
    }

    @Test
    void REGの署名はD01_D02_D03で判定する() {
        AtomicReference<FeatureEnablementSnapshot> current = new AtomicReference<>(FeatureEnablementTestSnapshots
                .builder(NOW).open(Feature.NF07_RETENTION)
                .signOff("NF07_D01", 6, 0, TODAY.minusDays(10), TODAY.plusDays(300))
                .signOff("NF07_D03", 3, 0, TODAY.minusDays(10), TODAY.plusDays(300)).build());
        PrivacySubgateSignOffs signOffs = signOffs(databaseSource(), current);

        assertThat(signOffs.valid(PrivacySubgateSignOffs.REGISTRATION)).as("D-02 が無ければ閉").isFalse();
    }

    @Test
    void DBで管理しなければ判定せず部品が揃わなければ閉() {
        AtomicReference<FeatureEnablementSnapshot> empty = new AtomicReference<>(
                FeatureEnablementTestSnapshots.builder(NOW).build());
        assertThat(signOffs(new FeatureEnablementProperties(), empty).valid(PrivacySubgateSignOffs.RELEASE))
                .as("property の源（dev・test）では従来どおり").isTrue();

        DefaultListableBeanFactory none = new DefaultListableBeanFactory();
        PrivacySubgateSignOffs missing = new PrivacySubgateSignOffs(databaseSource(), catalog,
                none.getBeanProvider(FeatureEnablementSnapshotHolder.class), none.getBeanProvider(SubgateMonitor.class),
                none.getBeanProvider(Clock.class));
        assertThat(missing.valid(PrivacySubgateSignOffs.RELEASE)).isFalse();
        assertThat(signOffs(databaseSource(), new AtomicReference<>(snapshot(null))).valid("UNKNOWN"))
                .as("目録に無い副入口は閉").isFalse();
    }

    private PrivacySubgateSignOffs signOffs(FeatureEnablementProperties properties,
                                            AtomicReference<FeatureEnablementSnapshot> current) {
        FeatureEnablementSnapshotHolder holder = mock(FeatureEnablementSnapshotHolder.class);
        when(holder.get()).thenAnswer(invocation -> current.get());
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("holder", holder);
        beans.registerSingleton("monitor", new SubgateMonitor(catalog, emptyConditions()));
        beans.registerSingleton("clock", CLOCK);
        return new PrivacySubgateSignOffs(properties, catalog, beans.getBeanProvider(FeatureEnablementSnapshotHolder.class),
                beans.getBeanProvider(SubgateMonitor.class), beans.getBeanProvider(Clock.class));
    }

    private static FeatureEnablementProperties databaseSource() {
        FeatureEnablementProperties properties = new FeatureEnablementProperties();
        properties.setSource(FeatureEnablementProperties.Source.DATABASE);
        return properties;
    }

    /** 保持・保全が開いていて、hold 開始・REL の署名が揃う snapshot（{@code without} の種別だけ確認済みの署名が無い）。 */
    private static FeatureEnablementSnapshot snapshot(String without) {
        FeatureEnablementTestSnapshots.Builder builder = FeatureEnablementTestSnapshots.builder(NOW)
                .open(Feature.NF07_RETENTION);
        String[] kinds = {"NF07_D05", "NF07_D05_OWNER_ACCEPTANCE", "NF07_D03", "NF07_TRUST_KMS", "NF07_TRUST_STAGING"};
        for (int i = 0; i < kinds.length; i++) {
            if (!kinds[i].equals(without)) {
                builder.signOff(kinds[i], i + 1, 0, TODAY.minusDays(10), TODAY.plusDays(300));
            }
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<SubgateMonitor.Condition> emptyConditions() {
        ObjectProvider<SubgateMonitor.Condition> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(invocation -> java.util.stream.Stream.empty());
        return provider;
    }
}
