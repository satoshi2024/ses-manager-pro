package com.ses.privacy.production;

import com.ses.common.exception.BusinessException;
import com.ses.config.feature.Feature;
import com.ses.entity.FeatureSignOff;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.condition.FeatureConditionCheck;
import com.ses.featureenablement.condition.FeatureEvaluationContext;
import com.ses.featureenablement.condition.SignOffConditionEvaluator;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import com.ses.privacy.production.PrivacyAccessGuard.Capability;
import com.ses.privacy.production.PrivacyDecisionBinding.Decision;
import com.ses.privacy.production.dsar.PrivacyDsarDecisions;
import com.ses.privacy.production.provider.DsarDeliveryClass;
import com.ses.privacy.production.redaction.ThirdPartyRedactor;
import com.ses.privacy.production.retention.hold.PrivacyHoldDecisions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FE-C01（feature-enablement-console design §8.5 の 1・§2.6.1。review P1-2・P2-N1）: NF07 の承認の値の源。
 *
 * <ul>
 *   <li>DB の源は確認済みの組の全ての member（状態に依らない）と確認済みの D-02・D-03・D-05 から値を返し、既存の golden vector
 *       （{@code a4ce05f1…}）は property の源と同じになる。</li>
 *   <li>期間は評価する開放単位の capability が要る決定だけで判定する（D-06 の失効で retention の評価は成立・dsar は不成立）。</li>
 *   <li>読む側（hold の前提・DSAR の前提・第三者 redaction）は呼出しごとに源を読み、組の差替えは次の呼出しから効く。</li>
 *   <li>組の整合の検査（D-09 の digest・D-06 の有無・tenant）。</li>
 * </ul>
 * 値は全て合成で、実署名ではない。
 */
class PrivacyApprovalSourceTest {

    private static final String TENANT = "tenant-a";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 14);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-14T03:00:00Z"), ZoneId.of("Asia/Tokyo"));
    private static final String GOLDEN = "a4ce05f19b45525b88857b66b6b03d8d2a793f60f3805d616c5719c7c6503404";
    private static final Set<Capability> RETENTION = EnumSet.of(Capability.RETENTION);
    private static final Set<Capability> DSAR = EnumSet.of(Capability.DSAR);

    @Test
    void DBの源は組のmemberから値を返しgolden_vectorはpropertyの源と同じ() {
        PrivacyApprovalProperties properties = PrivacyDecisionBindingTest.approved(List.of("retention"));
        assertThat(PrivacyDecisionBinding.computeDigest(PrivacyApprovalSource.properties(properties, null)))
                .isEqualTo(GOLDEN);

        FeatureEnablementSnapshot snapshot = snapshot(retentionMembers(GOLDEN), Map.of());
        PrivacyApprovalSource database = DatabasePrivacyApprovalSource.of(snapshot, 30);

        assertThat(PrivacyDecisionBinding.computeDigest(database)).isEqualTo(GOLDEN);
        PrivacyDecisionBinding.Evaluation evaluation = PrivacyDecisionBinding.evaluate(database, RETENTION, TENANT, TODAY);
        assertThat(evaluation.complete()).as(String.join(",", evaluation.violations())).isTrue();
        assertThat(evaluation.snapshot().approvalDigest()).isEqualTo(GOLDEN);
        assertThat(database.reference(Decision.D02)).as("組に属さない決定は確認済みの署名から読む").isNull();
    }

    @Test
    void 決定の束縛の入力は確認済みの組の全てのmemberでmemberの状態に依らない() {
        Map<String, FeatureEnablementSnapshot.BundleMemberView> members = dsarMembers();
        for (String status : List.of("CONFIRMED", "EXPIRED", "REVOKED", "PURGED")) {
            Map<String, FeatureEnablementSnapshot.BundleMemberView> withStatus = new LinkedHashMap<>(members);
            FeatureEnablementSnapshot.BundleMemberView d06 = members.get("NF07_D06");
            withStatus.put("NF07_D06", new FeatureEnablementSnapshot.BundleMemberView(d06.signOffId(), d06.kindCode(),
                    d06.reference(), d06.signedOn(), d06.expiresOn(), d06.structuredValues(), status));
            PrivacyApprovalSource database = DatabasePrivacyApprovalSource.of(snapshot(withStatus, Map.of()), 30);

            assertThat(database.reference(Decision.D06)).as(status).isNotNull();
            assertThat(database.dsarDueDays()).as(status).isEqualTo(30);
            assertThat(database.dsarPackageMaxAge()).as(status).isEqualTo(Duration.ofDays(30));
            assertThat(PrivacyDecisionBinding.evaluate(database, RETENTION, TENANT, TODAY).complete())
                    .as("D-06 の状態で保持・保全の束縛は変わらない: " + status).isTrue();
        }
    }

    @Test
    void D06の失効でretentionの評価は成立しdsarは不成立() {
        PrivacyApprovalSource database = DatabasePrivacyApprovalSource.of(snapshot(dsarMembers(), Map.of()), 30);
        LocalDate afterD06 = LocalDate.of(2027, 2, 1);

        PrivacyDecisionBinding.Evaluation retention = PrivacyDecisionBinding.evaluate(database, RETENTION, TENANT,
                afterD06, RETENTION);
        PrivacyDecisionBinding.Evaluation dsar = PrivacyDecisionBinding.evaluate(database, DSAR, TENANT, afterD06, DSAR);

        assertThat(retention.complete()).as(String.join(",", retention.violations())).isTrue();
        assertThat(dsar.complete()).isFalse();
        assertThat(dsar.violations()).containsExactly("d06.expiresOn.expired");
        assertThat(PrivacyDecisionBinding.window(database, afterD06, 30, RETENTION).state())
                .isEqualTo(PrivacyDecisionBinding.WindowState.VALID);
        assertThat(PrivacyDecisionBinding.window(database, afterD06, 30, DSAR).state())
                .isEqualTo(PrivacyDecisionBinding.WindowState.EXPIRED);
    }

    /** review P2-N1: D-06 が EXPIRED・REVOKED の後の評価でも、保持・保全の条件は成り立ち、DSAR だけが成り立たない。 */
    @Test
    void D06がEXPIREDやREVOKEDになった後の評価で保持保全は成立しDSARは不成立() {
        FeatureEnablementCatalog catalog = FeatureEnablementCatalog.standard();
        SignOffConditionEvaluator signOffs = new SignOffConditionEvaluator(catalog);
        for (String status : List.of("EXPIRED", "REVOKED")) {
            Map<String, FeatureEnablementSnapshot.BundleMemberView> members = new LinkedHashMap<>(dsarMembers());
            FeatureEnablementSnapshot.BundleMemberView d06 = members.get("NF07_D06");
            members.put("NF07_D06", new FeatureEnablementSnapshot.BundleMemberView(d06.signOffId(), d06.kindCode(),
                    d06.reference(), d06.signedOn(), d06.expiresOn(), d06.structuredValues(), status));
            // 確認済みの署名には D-06 が無い（EXPIRED・REVOKED は CONFIRMED でない）
            Map<String, FeatureEnablementSnapshot.ConfirmedSignOff> confirmed = new LinkedHashMap<>();
            for (String kind : List.of("NF07_D01", "NF07_D09", "NF07_BRIDGE", "NF07_DSAR_HOLD_ACCEPTANCE",
                    "NF07_DSAR_SELF_DELIVERY", "NF07_DSAR_I10", "NF07_DSAR_REDACTION")) {
                FeatureEnablementSnapshot.BundleMemberView member = members.get(kind);
                long id = member == null ? 900L + confirmed.size() : member.signOffId();
                confirmed.put(kind, new FeatureEnablementSnapshot.ConfirmedSignOff(id, 0, kind,
                        member == null ? "REF-" + id : member.reference(), "TECH_LEAD", LocalDate.of(2026, 11, 1),
                        LocalDate.of(2027, 10, 1), member == null ? null : member.structuredValues(), "DECISION"));
            }
            FeatureEnablementSnapshot snapshot = snapshot(members, confirmed);
            PrivacyApprovalSource database = DatabasePrivacyApprovalSource.of(snapshot, 30);
            FeatureEvaluationContext context = new FeatureEvaluationContext("default", TODAY, CLOCK, snapshot,
                    FeatureEvaluationContext.Purpose.PERIODIC, Set.of(), true);

            assertThat(PrivacyDecisionBinding.evaluate(database, RETENTION, TENANT, TODAY, RETENTION).complete())
                    .as(status).isTrue();
            assertThat(signOffs.evaluate(Feature.NF07_RETENTION, context)).as(status)
                    .allMatch(FeatureConditionCheck::passing);
            assertThat(signOffs.evaluate(Feature.NF07_DSAR, context)).as(status)
                    .extracting(FeatureConditionCheck::code).contains("signoff.NF07_D06.missing");
        }
    }

    @Test
    void 読む側は呼出しごとに源を読み組の差替えは次の呼出しから効く() {
        AtomicReference<PrivacyApprovalSource> current = new AtomicReference<>(
                DatabasePrivacyApprovalSource.of(snapshot(dsarMembers(), Map.of()), 30));
        PrivacyApprovalSource switching = new SwitchingSource(current);

        PrivacyDsarDecisions dsar = new PrivacyDsarDecisions(switching, new PrivacyFeatureProperties(), CLOCK);
        assertThat(dsar.evaluate().ready()).isTrue();
        assertThat(dsar.evaluate().dueDays()).isEqualTo(30);
        assertThat(dsar.packageMaxAge()).isEqualTo(Duration.ofDays(30));
        ThirdPartyRedactor redactor = new ThirdPartyRedactor(rulesProvider("D06-NF07-2026-0006"), switching);
        assertThat(redactor.redact(DsarDeliveryClass.SELF, List.of()).reason()).isEqualTo(ThirdPartyRedactor.RULES_APPLIED);
        PrivacyHoldDecisions hold = new PrivacyHoldDecisions(switching, null, null, CLOCK);
        assertThat(hold.releaseReadiness().missing()).containsExactlyInAnyOrder("d03", "d05");

        // 組を差し替える（D-06 の決定 ID と期限規則が変わり、D-03・D-05 が確認される）
        Map<String, FeatureEnablementSnapshot.BundleMemberView> replaced = new LinkedHashMap<>(dsarMembers());
        replaced.put("NF07_D06", member(13, "NF07_D06", "D06-NF07-2026-0606", "2026-11-05", "2027-01-31",
                "{\"dueDays\":\"14\",\"packageMaxAge\":\"PT12H\"}"));
        Map<String, FeatureEnablementSnapshot.ConfirmedSignOff> standalone = Map.of(
                "NF07_D03", confirmed(21, "NF07_D03", "D03-NF07-2026-0003"),
                "NF07_D05", confirmed(22, "NF07_D05", "D05-NF07-2026-0005"));
        current.set(DatabasePrivacyApprovalSource.of(snapshot(replaced, standalone), 30));

        assertThat(dsar.evaluate().dueDays()).isEqualTo(14);
        assertThat(dsar.packageMaxAge()).isEqualTo(Duration.ofHours(12));
        assertThat(redactor.redact(DsarDeliveryClass.SELF, List.of()).reason()).as("D-06 の決定 ID が変われば規則は使わない")
                .isEqualTo(ThirdPartyRedactor.RULES_UNSET);
        assertThat(hold.releaseReadiness().ready()).isTrue();
        assertThat(hold.releaseReadiness().d03DecisionId()).isEqualTo("D03-NF07-2026-0003");
    }

    @Test
    void 組の整合の検査はdigestの不一致とD06の過不足とtenantの不一致を400で拒む() {
        PrivacyDecisionBindingConsistencyCheck check = new PrivacyDecisionBindingConsistencyCheck();
        assertThat(check.bundleKind()).isEqualTo(FeatureEnablementCatalog.BUNDLE_NF07_DECISION_BINDING);
        Map<String, FeatureSignOff> valid = rows(dsarMembers());
        check.verify(TENANT, valid);

        Map<String, FeatureSignOff> mismatch = rows(dsarMembers());
        mismatch.get("NF07_D01").setReference("D01-NF07-2026-7777");
        assertInconsistent(() -> check.verify(TENANT, mismatch), PrivacyDecisionBinding.VIOLATION_DIGEST_MISMATCH);

        Map<String, FeatureSignOff> withoutD06 = rows(dsarMembers());
        withoutD06.remove("NF07_D06");
        assertInconsistent(() -> check.verify(TENANT, withoutD06), "d06.missing");

        Map<String, FeatureSignOff> retentionWithD06 = rows(retentionMembers(GOLDEN));
        retentionWithD06.put("NF07_D06", valid.get("NF07_D06"));
        assertInconsistent(() -> check.verify(TENANT, retentionWithD06), "d06.notApproved");

        assertInconsistent(() -> check.verify("tenant-b", valid), PrivacyDecisionBinding.VIOLATION_TENANT_MISMATCH);
        check.verify(TENANT, rows(retentionMembers(GOLDEN)));
    }

    // ---------------------------------------------------------------- helper

    /** retention を承認した組（D-01・D-09。golden vector と同じ値）。 */
    static Map<String, FeatureEnablementSnapshot.BundleMemberView> retentionMembers(String digest) {
        Map<String, FeatureEnablementSnapshot.BundleMemberView> members = new LinkedHashMap<>();
        members.put("NF07_D01", member(11, "NF07_D01", "D01-NF07-2026-0001", "2026-11-01", "2027-10-31", null));
        members.put("NF07_D09", member(12, "NF07_D09", "D09-NF07-2026-0009", "2026-11-10", "2027-05-31",
                "{\"approvalDigest\":\"" + digest + "\",\"capabilities\":\"retention\",\"tenantId\":\"" + TENANT + "\"}"));
        return members;
    }

    /** retention と dsar を承認した組（D-01・D-06・D-09。D-06 は 2027-01-31 に失効）。 */
    static Map<String, FeatureEnablementSnapshot.BundleMemberView> dsarMembers() {
        Map<String, FeatureEnablementSnapshot.BundleMemberView> members = new LinkedHashMap<>();
        members.put("NF07_D01", member(11, "NF07_D01", "D01-NF07-2026-0001", "2026-11-01", "2027-10-31", null));
        members.put("NF07_D06", member(13, "NF07_D06", "D06-NF07-2026-0006", "2026-11-05", "2027-01-31",
                "{\"dueDays\":\"30\",\"packageMaxAge\":\"30\"}"));
        members.put("NF07_D09", member(12, "NF07_D09", "D09-NF07-2026-0009", "2026-11-10", "2027-05-31", null));
        String digest = PrivacyDecisionBinding.computeDigest(DatabasePrivacyApprovalSource.of(snapshot(withD09(members,
                "0".repeat(64)), Map.of()), 30));
        return withD09(members, digest);
    }

    private static Map<String, FeatureEnablementSnapshot.BundleMemberView> withD09(
            Map<String, FeatureEnablementSnapshot.BundleMemberView> members, String digest) {
        Map<String, FeatureEnablementSnapshot.BundleMemberView> copy = new LinkedHashMap<>(members);
        copy.put("NF07_D09", member(12, "NF07_D09", "D09-NF07-2026-0009", "2026-11-10", "2027-05-31",
                "{\"approvalDigest\":\"" + digest + "\",\"capabilities\":\"dsar,retention\",\"tenantId\":\"" + TENANT
                        + "\"}"));
        return copy;
    }

    private static FeatureEnablementSnapshot.BundleMemberView member(long id, String kind, String reference,
                                                                     String signedOn, String expiresOn, String json) {
        return new FeatureEnablementSnapshot.BundleMemberView(id, kind, reference, LocalDate.parse(signedOn),
                LocalDate.parse(expiresOn), json, "CONFIRMED");
    }

    private static FeatureEnablementSnapshot.ConfirmedSignOff confirmed(long id, String kind, String reference) {
        return new FeatureEnablementSnapshot.ConfirmedSignOff(id, 0, kind, reference, "D03_OWNER",
                LocalDate.of(2026, 11, 1), LocalDate.of(2027, 6, 1), null, "DECISION");
    }

    private static FeatureEnablementSnapshot snapshot(Map<String, FeatureEnablementSnapshot.BundleMemberView> members,
                                                      Map<String, FeatureEnablementSnapshot.ConfirmedSignOff> signOffs) {
        Instant now = CLOCK.instant();
        return new FeatureEnablementSnapshot(1L, now, now, true, Map.of(), Map.of(), signOffs,
                Map.of(FeatureEnablementCatalog.BUNDLE_NF07_DECISION_BINDING, new FeatureEnablementSnapshot.BundleView(1L,
                        0, FeatureEnablementCatalog.BUNDLE_NF07_DECISION_BINDING, members)));
    }

    private static Map<String, FeatureSignOff> rows(Map<String, FeatureEnablementSnapshot.BundleMemberView> members) {
        Map<String, FeatureSignOff> rows = new LinkedHashMap<>();
        members.forEach((kind, member) -> {
            FeatureSignOff row = new FeatureSignOff();
            row.setId(member.signOffId());
            row.setKindCode(kind);
            row.setReference(member.reference());
            row.setSignedOn(member.signedOn());
            row.setExpiresOn(member.expiresOn());
            row.setStructuredValues(member.structuredValues());
            rows.put(kind, row);
        });
        return rows;
    }

    private static void assertInconsistent(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        BusinessException error = catchThrowableOfType(call, BusinessException.class);
        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(400);
        assertThat(error.getMessageKey()).isEqualTo(PrivacyDecisionBindingConsistencyCheck.MESSAGE_KEY);
        assertThat(error.getArgs()).containsExactly(code);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ThirdPartyRedactor.Rules> rulesProvider(String decisionId) {
        ObjectProvider<ThirdPartyRedactor.Rules> provider = mock(ObjectProvider.class);
        when(provider.getIfUnique()).thenReturn(new ThirdPartyRedactor.Rules(decisionId, Set.of()));
        return provider;
    }

    /** 呼出しごとに現在の源へ委ねる（組の差替えを模す）。 */
    private record SwitchingSource(AtomicReference<PrivacyApprovalSource> current) implements PrivacyApprovalSource {

        @Override
        public PrivacyApprovalSource resolve() {
            return current.get().resolve();
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
    }
}
