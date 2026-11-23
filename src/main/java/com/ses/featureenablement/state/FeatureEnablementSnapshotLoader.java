package com.ses.featureenablement.state;

import com.ses.config.feature.Feature;
import com.ses.entity.FeatureEnablementSetting;
import com.ses.entity.FeatureEnablementState;
import com.ses.entity.FeatureSignOff;
import com.ses.entity.FeatureSignOffBundle;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.condition.SignOffRequirements;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import com.ses.mapper.FeatureEnablementGenerationMapper;
import com.ses.mapper.FeatureEnablementSettingMapper;
import com.ses.mapper.FeatureEnablementStateMapper;
import com.ses.mapper.FeatureSignOffBundleMapper;
import com.ses.mapper.FeatureSignOffBundleMemberMapper;
import com.ses.mapper.FeatureSignOffMapper;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * DB から snapshot を作る（design §3.2）。照会は世代・開放状態・設定・確認済みの署名・確認済みの組・組の member の6回。
 *
 * <p>申請・承認・閉鎖・署名の確認等の書込みの transaction も、世代の行を lock した後にこの loader で同じ transaction の中から読み直し、
 * 条件の評価と指紋はその読直しで行う（memory の snapshot を使わない。READ COMMITTED で最新の commit を見る。review P2-3）。
 * 世代の行が無い（最初の書込みの前）は世代 0 として読めたことにする。{@code openUntil} は目録の要る署名の最も早い失効日（要る署名が
 * 1つでも無ければ {@code LocalDate.MIN}）。DB の時刻の列は JVM の既定の時刻帯で書くため（{@code rowTimestamp}）、同じ時刻帯で
 * {@link Instant} に直す。</p>
 */
@Component
public class FeatureEnablementSnapshotLoader {

    private final FeatureEnablementGenerationMapper generationMapper;
    private final FeatureEnablementStateMapper stateMapper;
    private final FeatureEnablementSettingMapper settingMapper;
    private final FeatureSignOffMapper signOffMapper;
    private final FeatureSignOffBundleMapper bundleMapper;
    private final FeatureSignOffBundleMemberMapper memberMapper;
    private final FeatureEnablementCatalog catalog;
    private final FeatureEnablementProperties properties;
    private final Clock clock;

    public FeatureEnablementSnapshotLoader(FeatureEnablementGenerationMapper generationMapper,
                                           FeatureEnablementStateMapper stateMapper,
                                           FeatureEnablementSettingMapper settingMapper,
                                           FeatureSignOffMapper signOffMapper,
                                           FeatureSignOffBundleMapper bundleMapper,
                                           FeatureSignOffBundleMemberMapper memberMapper,
                                           FeatureEnablementCatalog catalog,
                                           FeatureEnablementProperties properties,
                                           Clock clock) {
        this.generationMapper = generationMapper;
        this.stateMapper = stateMapper;
        this.settingMapper = settingMapper;
        this.signOffMapper = signOffMapper;
        this.bundleMapper = bundleMapper;
        this.memberMapper = memberMapper;
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.properties = properties;
        this.clock = clock;
    }

    /** 世代だけを読む（poll。行が無ければ 0）。 */
    public long readGeneration(String tenantId) {
        Long generation = generationMapper.selectGeneration(tenantId);
        return generation == null ? 0L : generation;
    }

    /** snapshot を読む（照会の失敗は例外のまま返す。呼出し側が fail-closed にする）。 */
    public FeatureEnablementSnapshot load(String tenantId) {
        Instant now = clock.instant();
        long generation = readGeneration(tenantId);

        Map<String, String> settings = new HashMap<>();
        for (FeatureEnablementSetting setting : settingMapper.selectByTenant(tenantId)) {
            if (setting.getSettingValue() != null && !setting.getSettingValue().isBlank()) {
                settings.put(setting.getSettingKey(), setting.getSettingValue());
            }
        }

        Map<String, FeatureEnablementSnapshot.ConfirmedSignOff> signOffs = new LinkedHashMap<>();
        for (FeatureSignOff row : signOffMapper.selectConfirmedByTenant(tenantId)) {
            signOffs.put(row.getKindCode(), new FeatureEnablementSnapshot.ConfirmedSignOff(row.getId(),
                    row.getVersion() == null ? 0 : row.getVersion(), row.getKindCode(), row.getReference(),
                    row.getSignerRole(), row.getSignedOn(), row.getExpiresOn(), row.getStructuredValues(),
                    row.getKindClass()));
        }

        Map<Long, Map<String, FeatureEnablementSnapshot.BundleMemberView>> membersByBundle = new HashMap<>();
        for (BundleMemberRow member : memberMapper.selectMembersOfConfirmedBundles(tenantId)) {
            membersByBundle.computeIfAbsent(member.getBundleId(), key -> new LinkedHashMap<>())
                    .put(member.getKindCode(), new FeatureEnablementSnapshot.BundleMemberView(member.getSignOffId(),
                            member.getKindCode(), member.getReference(), member.getSignedOn(), member.getExpiresOn(),
                            member.getStructuredValues(), member.getStatus()));
        }
        Map<String, FeatureEnablementSnapshot.BundleView> bundles = new LinkedHashMap<>();
        for (FeatureSignOffBundle bundle : bundleMapper.selectConfirmedByTenant(tenantId)) {
            bundles.put(bundle.getBundleKind(), new FeatureEnablementSnapshot.BundleView(bundle.getId(),
                    bundle.getVersion() == null ? 0 : bundle.getVersion(), bundle.getBundleKind(),
                    membersByBundle.getOrDefault(bundle.getId(), Map.of())));
        }

        Map<Feature, FeatureEnablementSnapshot.UnitView> units = new EnumMap<>(Feature.class);
        for (FeatureEnablementState row : stateMapper.selectByTenant(tenantId)) {
            Feature feature = Feature.valueOf(row.getFeatureKey());
            units.put(feature, new FeatureEnablementSnapshot.UnitView(EnablementState.of(row.getState()),
                    row.getVersion() == null ? 0 : row.getVersion(),
                    SignOffRequirements.openUntil(catalog, feature, signOffs, bundles, settings),
                    toInstant(row.getUnverifiableSince()), row.getClosedReason()));
        }
        return new FeatureEnablementSnapshot(generation, now, now, properties == null || properties.isAllowed(), units,
                settings, signOffs, bundles);
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }

    /** 目録（試験の補助）。 */
    FeatureEnablementCatalog catalog() {
        return catalog;
    }

    /** 開放状態の行の一覧（試験の補助）。 */
    List<FeatureEnablementState> states(String tenantId) {
        return stateMapper.selectByTenant(tenantId);
    }
}
