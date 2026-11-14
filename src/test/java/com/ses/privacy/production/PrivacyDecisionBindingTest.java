package com.ses.privacy.production;

import com.ses.config.AiConfig;
import com.ses.privacy.production.PrivacyAccessGuard.Capability;
import com.ses.privacy.production.PrivacyDecisionBinding.Decision;
import com.ses.service.ai.AiExternalSendApproval;
import com.ses.service.ai.AiExternalSendApprovalFixtures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W3-14b: NF07 の決定ID束縛（production-bridge-phased-plan.md §3.1.6）。値は全て合成で、実署名ではない。
 *
 * <p>規則（ID形式・placeholder・重複・署名日・失効日・有効期間・digest）が NF08 の {@link AiExternalSendApproval} と
 * 一致することを、同じ入力に対する判定の比較で固定する（乖離したらここで失敗する）。
 */
class PrivacyDecisionBindingTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 14);
    private static final String TENANT = "tenant-a";
    private static final Set<Capability> RETENTION = EnumSet.of(Capability.RETENTION);

    @Test
    void 完全な束縛はcompleteでdigestは固定の正規形のSHA256になる() throws Exception {
        PrivacyApprovalProperties approval = approved(List.of("retention"));

        PrivacyDecisionBinding.Evaluation evaluation = evaluate(approval, RETENTION, TENANT);

        assertTrue(evaluation.complete(), () -> String.join(",", evaluation.violations()));
        assertEquals(Set.of(Capability.RETENTION), evaluation.snapshot().capabilities());
        assertEquals("D01-NF07-2026-0001", evaluation.snapshot().decisionIds().get(Decision.D01));
        assertEquals("D09-NF07-2026-0009", evaluation.snapshot().decisionIds().get(Decision.D09));
        assertFalse(evaluation.snapshot().decisionIds().containsKey(Decision.D06), "retentionはD-06を束縛しない");

        String canonical = "nf07-privacy-decision-binding-v1\n"
                + "tenant-id=tenant-a\n"
                + "capabilities=retention\n"
                + "d01.decision-id=D01-NF07-2026-0001\n"
                + "d01.signed-on=2026-11-01\n"
                + "d01.expires-on=2027-10-31\n"
                + "d09.decision-id=D09-NF07-2026-0009\n"
                + "d09.signed-on=2026-11-10\n"
                + "d09.expires-on=2027-05-31\n";
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, PrivacyDecisionBinding.computeDigest(approval));
        assertEquals(expected, evaluation.snapshot().approvalDigest());
        // feature-switches.md 4.7 の算出例（printf | sha256sum）と同じ値
        assertEquals("a4ce05f19b45525b88857b66b6b03d8d2a793f60f3805d616c5719c7c6503404", expected);
    }

    @Test
    void 未設定は全項目の欠落を固定コードで返し未承認とする() {
        PrivacyDecisionBinding.Evaluation evaluation =
                evaluate(new PrivacyApprovalProperties(), RETENTION, TENANT);

        assertFalse(evaluation.complete());
        assertNull(evaluation.snapshot());
        assertTrue(evaluation.violations().containsAll(
                List.of("tenantId.missing", "capabilities.missing", "approvalDigest.missing")),
                () -> String.join(",", evaluation.violations()));
        assertNull(PrivacyDecisionBinding.computeDigest(new PrivacyApprovalProperties()));
        assertFalse(PrivacyDecisionBinding.configured(new PrivacyApprovalProperties()));
        PrivacyApprovalProperties partial = new PrivacyApprovalProperties();
        partial.getD09().setSignedOn("2026-11-10");
        assertTrue(PrivacyDecisionBinding.configured(partial), "部分設定を検出する");
    }

    @Test
    void 必要な決定の各項目が欠ければそれぞれの欠落コードで未承認とする() {
        List<String> fields = List.of("d01.decisionId", "d01.signedOn", "d01.expiresOn",
                "d09.decisionId", "d09.signedOn", "d09.expiresOn");
        for (String field : fields) {
            PrivacyApprovalProperties approval = approved(List.of("retention"));
            clear(approval, field);
            approval.setApprovalDigest(PrivacyDecisionBinding.computeDigest(approved(List.of("retention"))));

            PrivacyDecisionBinding.Evaluation evaluation = evaluate(approval, RETENTION, TENANT);

            assertFalse(evaluation.complete(), field);
            assertTrue(evaluation.violations().contains(field + ".missing"),
                    () -> field + ": " + String.join(",", evaluation.violations()));
        }
    }

    @Test
    void capabilityごとの必要な決定はretentionがD01とD09でdsarはD06も要る() {
        assertEquals(EnumSet.of(Decision.D01, Decision.D09), PrivacyDecisionBinding.requiredDecisions(Capability.RETENTION));
        assertEquals(EnumSet.of(Decision.D01, Decision.D06, Decision.D09),
                PrivacyDecisionBinding.requiredDecisions(Capability.DSAR));

        PrivacyApprovalProperties dsarWithoutD06 = approved(List.of("retention", "dsar"));
        PrivacyDecisionBinding.Evaluation missingD06 =
                evaluate(dsarWithoutD06, EnumSet.of(Capability.DSAR), TENANT);
        assertFalse(missingD06.complete());
        assertTrue(missingD06.violations().contains("d06.decisionId.missing"));

        PrivacyApprovalProperties both = approved(List.of("retention", "dsar"));
        both.getD06().setDecisionId("D06-NF07-2026-0006");
        both.getD06().setSignedOn("2026-11-05");
        both.getD06().setExpiresOn("2027-11-04");
        both.setApprovalDigest(PrivacyDecisionBinding.computeDigest(both));
        PrivacyDecisionBinding.Evaluation complete =
                evaluate(both, EnumSet.of(Capability.RETENTION, Capability.DSAR), TENANT);
        assertTrue(complete.complete(), () -> String.join(",", complete.violations()));
        assertEquals(Set.of(Capability.RETENTION, Capability.DSAR), complete.snapshot().capabilities());
    }

    @Test
    void ONのcapabilityが承認capabilityに無ければ未承認とする() {
        PrivacyApprovalProperties retentionOnly = approved(List.of("retention"));

        PrivacyDecisionBinding.Evaluation evaluation =
                evaluate(retentionOnly, EnumSet.of(Capability.RETENTION, Capability.DSAR), TENANT);

        assertFalse(evaluation.complete());
        assertEquals(List.of("capabilities.dsar.notApproved"), evaluation.violations());
    }

    @Test
    void 承認tenantが設定tenantと一致しなければ未承認とする() {
        PrivacyDecisionBinding.Evaluation other = evaluate(approved(List.of("retention")), RETENTION, "tenant-b");
        assertEquals(List.of(PrivacyDecisionBinding.VIOLATION_TENANT_MISMATCH), other.violations());

        PrivacyDecisionBinding.Evaluation caseVariant =
                evaluate(approved(List.of("retention")), RETENTION, "Tenant-A");
        assertEquals(List.of(PrivacyDecisionBinding.VIOLATION_TENANT_MISMATCH), caseVariant.violations(),
                "tenantは完全一致で比較する");

        PrivacyDecisionBinding.Evaluation absent = evaluate(approved(List.of("retention")), RETENTION, null);
        assertEquals(List.of(PrivacyDecisionBinding.VIOLATION_TENANT_MISMATCH), absent.violations());
    }

    @Test
    void 失効日以降と署名日前は未承認とする() {
        PrivacyApprovalProperties approval = approved(List.of("retention"));

        assertTrue(evaluate(approval, RETENTION, TENANT, LocalDate.of(2027, 5, 30)).complete(), "失効日の前日は有効");
        PrivacyDecisionBinding.Evaluation expired = evaluate(approval, RETENTION, TENANT, LocalDate.of(2027, 5, 31));
        assertEquals(List.of("d09.expiresOn.expired"), expired.violations(), "失効日当日から拒否する");

        PrivacyDecisionBinding.Evaluation future = evaluate(approval, RETENTION, TENANT, LocalDate.of(2026, 11, 9));
        assertEquals(List.of("d09.signedOn.future"), future.violations());
    }

    @Test
    void digestの不一致と形式不正は未承認とする() {
        PrivacyApprovalProperties tampered = approved(List.of("retention"));
        tampered.getD09().setDecisionId("D09-NF07-2026-0099");
        PrivacyDecisionBinding.Evaluation decisionChanged = evaluate(tampered, RETENTION, TENANT);
        assertEquals(List.of(PrivacyDecisionBinding.VIOLATION_DIGEST_MISMATCH), decisionChanged.violations());
        assertFalse(String.join(",", decisionChanged.violations()).contains("D09-NF07-2026-0099"),
                "設定値を違反コードへ出さない");

        PrivacyApprovalProperties expiryExtended = approved(List.of("retention"));
        expiryExtended.getD01().setExpiresOn("2027-11-01");
        assertEquals(List.of(PrivacyDecisionBinding.VIOLATION_DIGEST_MISMATCH),
                evaluate(expiryExtended, RETENTION, TENANT).violations());

        PrivacyApprovalProperties capabilityAdded = approved(List.of("retention"));
        capabilityAdded.setCapabilities(new ArrayList<>(List.of("retention", "dsar")));
        capabilityAdded.getD06().setDecisionId("D06-NF07-2026-0006");
        capabilityAdded.getD06().setSignedOn("2026-11-05");
        capabilityAdded.getD06().setExpiresOn("2027-11-04");
        assertEquals(List.of(PrivacyDecisionBinding.VIOLATION_DIGEST_MISMATCH),
                evaluate(capabilityAdded, RETENTION, TENANT).violations());

        PrivacyApprovalProperties uppercase = approved(List.of("retention"));
        uppercase.setApprovalDigest(uppercase.getApprovalDigest().toUpperCase());
        assertTrue(evaluate(uppercase, RETENTION, TENANT).complete(), "digestは小文字化して比較する（NF08と同じ）");

        PrivacyApprovalProperties malformed = approved(List.of("retention"));
        malformed.setApprovalDigest("sha256:" + malformed.getApprovalDigest());
        assertEquals(List.of("approvalDigest.format"), evaluate(malformed, RETENTION, TENANT).violations());
    }

    @Test
    void 一つの決定IDを複数の決定へ転記した束縛は未承認とする() {
        PrivacyApprovalProperties approval = approved(List.of("retention"));
        approval.getD09().setDecisionId("d01-nf07-2026-0001");
        approval.setApprovalDigest(PrivacyDecisionBinding.computeDigest(approved(List.of("retention"))));

        PrivacyDecisionBinding.Evaluation evaluation = evaluate(approval, RETENTION, TENANT);

        assertTrue(evaluation.violations().contains("decisionIds.notDistinct"),
                () -> String.join(",", evaluation.violations()));
        assertNull(PrivacyDecisionBinding.computeDigest(approval));
    }

    @Test
    void capabilityの形式不正と未対応の値は未承認とする() {
        PrivacyApprovalProperties homoglyph = approved(List.of("retention"));
        homoglyph.setCapabilities(new ArrayList<>(List.of("retentıon")));
        assertTrue(evaluate(homoglyph, RETENTION, TENANT).violations().contains("capabilities.format"));

        PrivacyApprovalProperties unknown = approved(List.of("retention"));
        unknown.setCapabilities(new ArrayList<>(List.of("retention", "disposition")));
        assertTrue(evaluate(unknown, RETENTION, TENANT).violations().contains("capabilities.unsupported"));

        PrivacyApprovalProperties upper = approved(List.of(" RETENTION "));
        assertTrue(evaluate(upper, RETENTION, TENANT).complete(), "capabilityは前後空白を除き小文字化して比較する");
        assertEquals(PrivacyDecisionBinding.computeDigest(approved(List.of("retention"))),
                PrivacyDecisionBinding.computeDigest(upper), "正規形は小文字");
    }

    @Test
    void 決定IDの形式とplaceholderの判定はNF08の外部送信承認と一致する() {
        List<String> candidates = new ArrayList<>(List.of(
                "D01-NF07-2026-0001", "", "   ", "abc", "-D01-2026", "D01 2026 0001", "D01/2026/0001",
                "D01-TBD-0001", "pending-0001", "Unknown-2026", "D01-DRAFT-1", "XXXX-0001", "CHANGE-ME-01",
                "NONE", "null", "N-A", "D01-2026-ＡＢ", "D01:2026.0001_a", "a".repeat(64), "a".repeat(65)));
        for (String candidate : candidates) {
            AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
            config.getExternalSendApproval().setDecisionId(candidate);
            String nf08 = classify(AiExternalSendApproval.evaluate(config, TODAY).violations(), "decisionId.");

            PrivacyApprovalProperties approval = approved(List.of("retention"));
            approval.getD01().setDecisionId(candidate);
            String nf07 = classify(evaluate(approval, RETENTION, TENANT).violations(), "d01.decisionId.");

            assertEquals(nf08, nf07, () -> "決定ID「" + candidate + "」の判定がNF08と異なる");
        }
    }

    @Test
    void 署名日と失効日の判定はNF08の外部送信承認と一致する() {
        String[][] cases = {
                {"2026-11-01", "2027-10-31"},
                {"2026-11-15", "2027-05-31"},
                {"2025-11-01", "2026-11-14"},
                {"2026-11-10", "2026-11-10"},
                {"2026-11-10", "2026-11-09"},
                {"2026-11-01", "2027-11-02"},
                {"2026-11-01", "2027-11-03"},
                {"2026/11/01", "2027-05-31"},
                {"", "2027-05-31"},
                {"2026-11-01", ""},
        };
        for (String[] dates : cases) {
            AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
            config.getExternalSendApproval().setSignedOn(dates[0]);
            config.getExternalSendApproval().setExpiresOn(dates[1]);
            Set<String> nf08 = dateCodes(AiExternalSendApproval.evaluate(config, TODAY).violations(), "");

            PrivacyApprovalProperties approval = approved(List.of("retention"));
            approval.getD01().setSignedOn(dates[0]);
            approval.getD01().setExpiresOn(dates[1]);
            Set<String> nf07 = dateCodes(evaluate(approval, RETENTION, TENANT).violations(), "d01.");

            assertEquals(nf08, nf07, () -> "署名日" + dates[0] + "・失効日" + dates[1] + "の判定がNF08と異なる");
        }
    }

    @Test
    void 業務日はJSTで判定する() {
        Clock lateUtc = Clock.fixed(Instant.parse("2026-11-13T15:30:00Z"), ZoneOffset.UTC);
        assertEquals(LocalDate.of(2026, 11, 14), PrivacyDecisionBinding.businessDate(lateUtc));
        assertNotNull(PrivacyDecisionBinding.businessDate(null));
    }

    // ---------------------------------------------------------------- helpers

    private static PrivacyDecisionBinding.Evaluation evaluate(PrivacyApprovalProperties approval,
                                                              Set<Capability> enabled, String tenant) {
        return evaluate(approval, enabled, tenant, TODAY);
    }

    private static PrivacyDecisionBinding.Evaluation evaluate(PrivacyApprovalProperties approval,
                                                              Set<Capability> enabled, String tenant,
                                                              LocalDate today) {
        return PrivacyDecisionBinding.evaluate(approval, enabled, tenant, today);
    }

    /** 有効期間内（TODAY基準）の完全な束縛。digestは決定書への記載を模して算出する。 */
    static PrivacyApprovalProperties approved(List<String> capabilities) {
        PrivacyApprovalProperties approval = new PrivacyApprovalProperties();
        approval.setTenantId(TENANT);
        approval.setCapabilities(new ArrayList<>(capabilities));
        approval.getD01().setDecisionId("D01-NF07-2026-0001");
        approval.getD01().setSignedOn("2026-11-01");
        approval.getD01().setExpiresOn("2027-10-31");
        approval.getD09().setDecisionId("D09-NF07-2026-0009");
        approval.getD09().setSignedOn("2026-11-10");
        approval.getD09().setExpiresOn("2027-05-31");
        approval.setApprovalDigest(PrivacyDecisionBinding.computeDigest(approval));
        return approval;
    }

    private static void clear(PrivacyApprovalProperties approval, String field) {
        String[] parts = field.split("\\.");
        PrivacyApprovalProperties.DecisionReference reference = "d01".equals(parts[0]) ? approval.getD01()
                : "d06".equals(parts[0]) ? approval.getD06() : approval.getD09();
        Consumer<String> setter = switch (parts[1]) {
            case "decisionId" -> reference::setDecisionId;
            case "signedOn" -> reference::setSignedOn;
            default -> reference::setExpiresOn;
        };
        setter.accept(" ");
    }

    private static String classify(List<String> violations, String prefix) {
        for (String kind : List.of("missing", "format", "placeholder")) {
            if (violations.contains(prefix + kind)) {
                return kind;
            }
        }
        return "valid";
    }

    private static Set<String> dateCodes(List<String> violations, String prefix) {
        Set<String> codes = new TreeSet<>();
        for (String violation : violations) {
            if (!violation.startsWith(prefix)) {
                continue;
            }
            String code = violation.substring(prefix.length());
            if (code.startsWith("signedOn.") || code.startsWith("expiresOn.")) {
                codes.add(code);
            }
        }
        return codes;
    }
}
