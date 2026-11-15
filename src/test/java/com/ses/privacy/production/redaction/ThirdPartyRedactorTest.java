package com.ses.privacy.production.redaction;

import com.ses.privacy.production.PrivacyApprovalProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 第三者 redaction の枠: 規則未設定なら自動出力0、規則があっても第三者の field は出さない（§3.2、§5.2 A2b）。 */
class ThirdPartyRedactorTest {

    private static final String D06 = "D06-NF07-2026-001";

    private static final List<ThirdPartyRedactor.Field> FIELDS = List.of(
            new ThirdPartyRedactor.Field("t_engineer.phone", "090", ThirdPartyRedactor.Ownership.SUBJECT),
            new ThirdPartyRedactor.Field("t_engineer.remarks", "memo", ThirdPartyRedactor.Ownership.SUBJECT),
            new ThirdPartyRedactor.Field("t_document.counterparty", "customer", ThirdPartyRedactor.Ownership.THIRD_PARTY),
            new ThirdPartyRedactor.Field("t_resume_ingestion.extracted_text", "text", null));

    @Test
    void Spring構成で規則のBeanが無ければ全fieldを要手動確認に回し自動出力は0() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        PrivacyApprovalProperties properties = new PrivacyApprovalProperties();
        properties.getD06().setDecisionId(D06);
        beans.registerSingleton("privacyApprovalProperties", properties);
        ThirdPartyRedactor redactor = new ThirdPartyRedactor(beans.getBeanProvider(PrivacyApprovalProperties.class),
                beans.getBeanProvider(ThirdPartyRedactor.Rules.class));

        ThirdPartyRedactor.Outcome outcome = redactor.redact(FIELDS);

        assertThat(outcome.reason()).isEqualTo(ThirdPartyRedactor.RULES_UNSET);
        assertThat(outcome.autoReleasedCount()).isZero();
        assertThat(outcome.manualReview()).extracting(ThirdPartyRedactor.Field::fieldId).containsExactly(
                "t_engineer.phone", "t_engineer.remarks", "t_document.counterparty", "t_resume_ingestion.extracted_text");
        assertThat(outcome.withheldFieldIds()).isEmpty();
    }

    @Test
    void 規則の決定IDが空またはD06の束縛と一致しなければ規則未設定として扱う() {
        Set<String> all = Set.of("t_engineer.phone", "t_engineer.remarks", "t_document.counterparty");
        for (ThirdPartyRedactor redactor : List.of(
                new ThirdPartyRedactor(() -> new ThirdPartyRedactor.Rules(" ", all), () -> D06),
                new ThirdPartyRedactor(() -> new ThirdPartyRedactor.Rules("D06-OTHER", all), () -> D06),
                new ThirdPartyRedactor(() -> new ThirdPartyRedactor.Rules(D06, all), () -> null),
                new ThirdPartyRedactor(() -> null, () -> D06),
                new ThirdPartyRedactor(() -> {
                    throw new IllegalStateException("broken");
                }, () -> D06))) {
            ThirdPartyRedactor.Outcome outcome = redactor.redact(FIELDS);
            assertThat(outcome.reason()).isEqualTo(ThirdPartyRedactor.RULES_UNSET);
            assertThat(outcome.autoReleasedCount()).isZero();
            assertThat(outcome.manualReview()).hasSize(FIELDS.size());
        }
    }

    @Test
    void 規則があっても第三者のfieldは出さず帰属不明と規則外の本人fieldは要手動確認にする() {
        ThirdPartyRedactor redactor = new ThirdPartyRedactor(() -> new ThirdPartyRedactor.Rules(D06,
                Set.of("t_engineer.phone", "t_document.counterparty", "t_resume_ingestion.extracted_text")), () -> D06);

        ThirdPartyRedactor.Outcome outcome = redactor.redact(FIELDS);

        assertThat(outcome.reason()).isEqualTo(ThirdPartyRedactor.RULES_APPLIED);
        assertThat(outcome.autoReleased()).extracting(ThirdPartyRedactor.Field::fieldId)
                .containsExactly("t_engineer.phone");
        assertThat(outcome.withheldFieldIds()).containsExactly("t_document.counterparty");
        assertThat(outcome.manualReview()).extracting(ThirdPartyRedactor.Field::fieldId)
                .containsExactly("t_engineer.remarks", "t_resume_ingestion.extracted_text");
        assertThat(redactor.redact(null).autoReleasedCount()).isZero();
    }
}
