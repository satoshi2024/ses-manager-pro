package com.ses.privacy.production;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import com.ses.featureenablement.state.FeatureEnablementSnapshotHolder;
import com.ses.privacy.production.PrivacyDecisionBinding.Decision;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * NF07 の承認の値の DB の源（feature-enablement-console design §8.5 の 1・§2.6.1。FE-C01）。開放状態の snapshot（memory）だけを読み、
 * DB に往復しない。
 *
 * <ul>
 *   <li>D-01・D-06・D-09: 確認済みの組「NF07 の決定の束縛」の全ての member（{@code BundleMemberView}）。member の署名の行の状態
 *       （{@code EXPIRED}・{@code REVOKED}・{@code PURGED} の後も残る参照と日付）に関係なく返す（review P2-N1）。D-06 が失効しても
 *       D-09 の digest は合ったままで、保持・保全の束縛は成り立つ（D-06 を要する DSAR だけを署名の条件が閉じる）。</li>
 *   <li>D-02・D-03・D-05: 確認済みの署名（組に属さない。1件ずつ確認する）。</li>
 *   <li>D-06 の {@code dueDays}・{@code packageMaxAge}、D-09 の {@code tenantId}・{@code capabilities}・{@code approvalDigest} は
 *       署名の構造化された値（approval digest には D-06 の2つの値を入れない。正規形 v1 を変えない。§18 J-15）。</li>
 * </ul>
 * 失効の警告日数は承認内容ではないため property の源の値を使う。
 */
public class DatabasePrivacyApprovalSource implements PrivacyApprovalSource {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<Decision> STANDALONE = List.of(Decision.D02, Decision.D03, Decision.D05);

    private final FeatureEnablementSnapshotHolder holder;
    private final PrivacyApprovalSource operational;

    /**
     * @param holder      開放状態の snapshot
     * @param operational 運用値（失効の警告日数）を読む源（property の源）
     */
    public DatabasePrivacyApprovalSource(FeatureEnablementSnapshotHolder holder, PrivacyApprovalSource operational) {
        this.holder = Objects.requireNonNull(holder, "holder");
        this.operational = operational;
    }

    @Override
    public PrivacyApprovalSource resolve() {
        return of(holder.get(), operational == null ? PrivacyDecisionWindowMonitor.DEFAULT_WARNING_DAYS
                : operational.expiryWarningDays());
    }

    @Override
    public PrivacyApprovalSource forSnapshot(FeatureEnablementSnapshot snapshot) {
        return snapshot == null ? resolve() : of(snapshot, expiryWarningDays());
    }

    /** snapshot から値を写す（試験と組の評価で使う）。 */
    public static PrivacyApprovalSource of(FeatureEnablementSnapshot snapshot, int expiryWarningDays) {
        Map<String, SignOffValue> rows = new LinkedHashMap<>();
        if (snapshot != null) {
            FeatureEnablementSnapshot.BundleView bundle =
                    snapshot.bundles().get(FeatureEnablementCatalog.BUNDLE_NF07_DECISION_BINDING);
            if (bundle != null) {
                for (FeatureEnablementSnapshot.BundleMemberView member : bundle.members().values()) {
                    rows.put(member.kindCode(), new SignOffValue(member.reference(), member.signedOn(), member.expiresOn(),
                            structured(member.structuredValues())));
                }
            }
            for (Decision decision : STANDALONE) {
                FeatureEnablementSnapshot.ConfirmedSignOff signOff = snapshot.signOffs().get(PrivacyApprovalSource.kindCode(decision));
                if (signOff != null) {
                    rows.put(signOff.kindCode(), new SignOffValue(signOff.reference(), signOff.signedOn(),
                            signOff.expiresOn(), structured(signOff.structuredValues())));
                }
            }
        }
        return PrivacyApprovalSource.fromSignOffs(rows, expiryWarningDays);
    }

    @Override
    public String tenantId() {
        return resolve().tenantId();
    }

    @Override
    public List<String> capabilities() {
        return resolve().capabilities();
    }

    @Override
    public String approvalDigest() {
        return resolve().approvalDigest();
    }

    @Override
    public DecisionValue reference(Decision decision) {
        return resolve().reference(decision);
    }

    @Override
    public Integer dsarDueDays() {
        return resolve().dsarDueDays();
    }

    @Override
    public Duration dsarPackageMaxAge() {
        return resolve().dsarPackageMaxAge();
    }

    @Override
    public int expiryWarningDays() {
        return operational == null ? PrivacyDecisionWindowMonitor.DEFAULT_WARNING_DAYS : operational.expiryWarningDays();
    }

    /** 構造化された値の JSON を読む（読めなければ空。束縛は欠けた値の違反になる）。 */
    static Map<String, String> structured(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<TreeMap<String, String>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
