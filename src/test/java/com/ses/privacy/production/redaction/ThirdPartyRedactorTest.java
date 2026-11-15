package com.ses.privacy.production.redaction;

import com.ses.privacy.production.PrivacyApprovalProperties;
import com.ses.privacy.production.ProductionPrivacyProviderRegistry;
import com.ses.privacy.production.provider.DocumentFileProvider;
import com.ses.privacy.production.provider.DsarDeliveryClass;
import com.ses.privacy.production.provider.PrivacyReadOnlyQuery;
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

        ThirdPartyRedactor.Outcome outcome = redactor.redact(DsarDeliveryClass.SELF, FIELDS);

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
            ThirdPartyRedactor.Outcome outcome = redactor.redact(DsarDeliveryClass.SELF, FIELDS);
            assertThat(outcome.reason()).isEqualTo(ThirdPartyRedactor.RULES_UNSET);
            assertThat(outcome.autoReleasedCount()).isZero();
            assertThat(outcome.manualReview()).hasSize(FIELDS.size());
        }
    }

    @Test
    void 規則があっても第三者のfieldは出さず帰属不明と規則外の本人fieldは要手動確認にする() {
        ThirdPartyRedactor redactor = new ThirdPartyRedactor(() -> new ThirdPartyRedactor.Rules(D06,
                Set.of("t_engineer.phone", "t_document.counterparty", "t_resume_ingestion.extracted_text")), () -> D06);

        ThirdPartyRedactor.Outcome outcome = redactor.redact(DsarDeliveryClass.SELF, FIELDS);

        assertThat(outcome.reason()).isEqualTo(ThirdPartyRedactor.RULES_APPLIED);
        assertThat(outcome.autoReleased()).extracting(ThirdPartyRedactor.Field::fieldId)
                .containsExactly("t_engineer.phone");
        assertThat(outcome.withheldFieldIds()).containsExactly("t_document.counterparty");
        assertThat(outcome.manualReview()).extracting(ThirdPartyRedactor.Field::fieldId)
                .containsExactly("t_engineer.remarks", "t_resume_ingestion.extracted_text");
        assertThat(redactor.redact(DsarDeliveryClass.SELF, null).autoReleasedCount()).isZero();
    }

    /**
     * 第三者混在（MIXED_THIRD_PARTY）の data element は、D-06 の規則が全 field の自動交付を許していても1件も自動で出さない。
     * 文書種別の交付区分は DocumentFileProvider の宣言から registry が引き、宣言の無い data element も第三者混在として扱う
     * （NF07-F2 delta review 第4項）。
     */
    @Test
    void 第三者混在の文書は規則が全fieldを許しても自動packageに入らない() {
        Set<String> all = Set.of("t_engineer.phone", "t_engineer.remarks", "t_document.counterparty",
                "t_resume_ingestion.extracted_text", "t_document_version.storage_key");
        ThirdPartyRedactor redactor = new ThirdPartyRedactor(() -> new ThirdPartyRedactor.Rules(D06, all), () -> D06);
        ProductionPrivacyProviderRegistry registry = new ProductionPrivacyProviderRegistry(
                List.of(new DocumentFileProvider(org.mockito.Mockito.mock(PrivacyReadOnlyQuery.class))));
        List<ThirdPartyRedactor.Field> subjectOnly = List.of(
                new ThirdPartyRedactor.Field("t_engineer.phone", "090", ThirdPartyRedactor.Ownership.SUBJECT),
                new ThirdPartyRedactor.Field("t_document_version.storage_key", "f.pdf", ThirdPartyRedactor.Ownership.SUBJECT));

        int mixedTypes = 0;
        for (var entry : DocumentFileProvider.DOCUMENT_TYPE_RULES.entrySet()) {
            DsarDeliveryClass deliveryClass = registry.deliveryClassOf("DOCUMENT:" + entry.getKey());
            assertThat(deliveryClass).as(entry.getKey()).isEqualTo(entry.getValue().deliveryClass());
            if (deliveryClass == DsarDeliveryClass.MIXED_THIRD_PARTY) {
                mixedTypes++;
                ThirdPartyRedactor.Outcome outcome = redactor.redact(deliveryClass, subjectOnly);
                assertThat(outcome.autoReleasedCount()).as(entry.getKey()).isZero();
                assertThat(outcome.reason()).isEqualTo(ThirdPartyRedactor.MIXED_MANUAL_ONLY);
                assertThat(outcome.manualReview()).hasSize(subjectOnly.size());
            }
        }
        assertThat(mixedTypes).isEqualTo(18);
        for (String undeclared : List.of("DOCUMENT:NEW_TYPE", "AUDIT_LOG")) {
            assertThat(redactor.redact(registry.deliveryClassOf(undeclared), subjectOnly).autoReleasedCount())
                    .as(undeclared).isZero();
        }
        assertThat(redactor.redact(null, subjectOnly).autoReleasedCount()).isZero();
        // SELF の種別だけが規則に従って自動交付し得る
        assertThat(redactor.redact(registry.deliveryClassOf("DOCUMENT:SKILL_SHEET"), subjectOnly).autoReleasedCount())
                .isEqualTo(2);
    }
}
