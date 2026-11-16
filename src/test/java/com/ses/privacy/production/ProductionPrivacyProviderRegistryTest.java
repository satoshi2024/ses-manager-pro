package com.ses.privacy.production;

import com.ses.privacy.production.provider.AiLogProvider;
import com.ses.privacy.production.provider.CandidateProvider;
import com.ses.privacy.production.provider.DocumentFileProvider;
import com.ses.privacy.production.provider.DsarDeliveryClass;
import com.ses.privacy.production.provider.EngineerProfileProvider;
import com.ses.privacy.production.provider.IngestionTextProvider;
import com.ses.privacy.production.provider.PrivacyReadOnlyQuery;
import com.ses.privacy.production.provider.PrivacyRecord;
import com.ses.privacy.production.provider.PrivacySubjectRef;
import com.ses.privacy.production.provider.PrivacySubjectType;
import com.ses.privacy.production.provider.ProductionPrivacyProvider;
import com.ses.privacy.production.provider.ProviderDataElement;
import com.ses.privacy.production.provider.ProviderTable;
import com.ses.privacy.production.provider.SubjectLink;
import com.ses.privacy.production.provider.SysUserProvider;
import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** F2 本番 provider registry: 未登録は PROVIDER_UNREGISTERED、重複・外部 provider・秘密列・未分類の宣言は起動拒否。 */
class ProductionPrivacyProviderRegistryTest {

    private final PrivacyReadOnlyQuery query = mock(PrivacyReadOnlyQuery.class);

    @Test
    void 初期6providerを登録しDBに触れず未登録は理由コード付きで拒否する() {
        ProductionPrivacyProviderRegistry registry = new ProductionPrivacyProviderRegistry(realProviders());

        assertThat(registry.providers()).extracting(ProductionPrivacyProvider::providerId)
                .containsExactly("ai-log", "candidate", "document-file", "engineer-profile", "ingestion-text", "sys-user");
        assertThat(registry.require("sys-user")).isInstanceOf(SysUserProvider.class);
        assertThat(registry.find(null)).isEmpty();
        assertThatThrownBy(() -> registry.require("audit-log"))
                .isInstanceOfSatisfying(ProductionPrivacyProviderRegistry.ProviderUnregisteredException.class, e -> {
                    assertThat(e.reasonCode()).isEqualTo(ProductionPrivacyProviderRegistry.PROVIDER_UNREGISTERED);
                    assertThat(e.providerId()).isEqualTo("audit-log");
                });
        verifyNoInteractions(query);
    }

    @Test
    void data_elementからproviderを引き文書種別は接頭辞で照合する() {
        ProductionPrivacyProviderRegistry registry = new ProductionPrivacyProviderRegistry(realProviders());

        assertThat(registry.providerFor("DOCUMENT:CONTRACT")).get()
                .extracting(ProductionPrivacyProvider::providerId).isEqualTo("document-file");
        assertThat(registry.providerFor("DOCUMENT:")).isEmpty();
        assertThat(registry.providerFor("RESUME_INGESTION_TEXT")).get()
                .extracting(ProductionPrivacyProvider::providerId).isEqualTo("ingestion-text");
        assertThat(registry.providerFor("AUDIT_LOG")).isEmpty();
        assertThat(registry.providerFor("engineer_profile")).isEmpty();
        assertThat(registry.providerFor(null)).isEmpty();
        // 個別の宣言が接頭辞の宣言より優先されるのではなく、接頭辞の宣言が個別の宣言を除外している
        assertThat(ProductionPrivacyProviderRegistry.declaration(registry.require("document-file"), "DOCUMENT:IMPORT"))
                .get().satisfies(e -> {
                    assertThat(e.prefixMatch()).isFalse();
                    assertThat(e.attribution()).isEqualTo(SubjectAttribution.NOT_ATTRIBUTABLE);
                });
        assertThat(ProductionPrivacyProviderRegistry.declaration(registry.require("document-file"), "DOCUMENT:NEW_TYPE"))
                .get().satisfies(e -> assertThat(e.prefixMatch()).isTrue());
    }

    @Test
    void DSARの交付区分は宣言から引き宣言が無ければ第三者混在として扱う() {
        ProductionPrivacyProviderRegistry registry = new ProductionPrivacyProviderRegistry(realProviders());

        assertThat(registry.deliveryClassOf("DOCUMENT:SKILL_SHEET")).isEqualTo(DsarDeliveryClass.SELF);
        assertThat(registry.deliveryClassOf("DOCUMENT:CONTRACT")).isEqualTo(DsarDeliveryClass.MIXED_THIRD_PARTY);
        assertThat(registry.deliveryClassOf("DOCUMENT:INVOICE_OUT")).isEqualTo(DsarDeliveryClass.MIXED_THIRD_PARTY);
        assertThat(registry.deliveryClassOf("DOCUMENT:NEW_TYPE")).isEqualTo(DsarDeliveryClass.MIXED_THIRD_PARTY);
        assertThat(registry.deliveryClassOf("AUDIT_LOG")).isEqualTo(DsarDeliveryClass.MIXED_THIRD_PARTY);
        assertThat(registry.deliveryClassOf(null)).isEqualTo(DsarDeliveryClass.MIXED_THIRD_PARTY);
    }

    @Test
    void 接頭辞の宣言は除外した個別の宣言とは重ならないが除外しなければ重なりとして拒否する() {
        ProviderDataElement exact = ProviderDataElement.exact("DOC:A", SubjectAttribution.NOT_ATTRIBUTABLE, List.of());
        ProviderDataElement excluding = ProviderDataElement.prefix("DOC:", java.util.Set.of("DOC:A"),
                SubjectAttribution.ATTRIBUTABLE, DsarDeliveryClass.MIXED_THIRD_PARTY, List.of(link()));
        assertThat(excluding.overlaps(exact)).isFalse();
        assertThat(new ProductionPrivacyProviderRegistry(List.of(new FakeProvider("fake-provider",
                List.of(exact, excluding), table()))).providerFor("DOC:A")).isPresent();

        assertThatThrownBy(() -> new ProductionPrivacyProviderRegistry(List.of(new FakeProvider("fake-provider",
                List.of(exact, ProviderDataElement.prefix("DOC:", SubjectAttribution.ATTRIBUTABLE, List.of(link()))),
                table()))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("重なって");
        assertThatThrownBy(() -> new ProviderDataElement("DOC:B", false, java.util.Set.of("DOC:C"),
                SubjectAttribution.ATTRIBUTABLE, DsarDeliveryClass.SELF, List.of(link())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static SubjectLink link() {
        return new SubjectLink(PrivacySubjectType.ENGINEER, "t_fake.engineer_id");
    }

    @Test
    void Springの登録が無ければ空のregistryになる() {
        ProductionPrivacyProviderRegistry registry = new ProductionPrivacyProviderRegistry(
                new DefaultListableBeanFactory().getBeanProvider(ProductionPrivacyProvider.class));

        assertThat(registry.providers()).isEmpty();
        assertThatThrownBy(() -> registry.require("engineer-profile"))
                .isInstanceOf(ProductionPrivacyProviderRegistry.ProviderUnregisteredException.class);
    }

    @Test
    void 重複したprovider_IDは起動を拒否する() {
        assertThatThrownBy(() -> new ProductionPrivacyProviderRegistry(List.of(
                fake("same-id", "ELEMENT_A"), fake("same-id", "ELEMENT_B"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("重複");
    }

    @Test
    void 外部providerは登録を拒否する() {
        for (String id : List.of("gemini-adapter", "cloudsign", "freee-accounting", "mailbox-reader", "backup-replica",
                "db-replica", "external-copy")) {
            assertThatThrownBy(() -> new ProductionPrivacyProviderRegistry(List.of(fake(id, "ELEMENT_" + id.length()))))
                    .as(id).isInstanceOf(IllegalStateException.class).hasMessageContaining("外部 provider");
        }
        for (String id : List.of("Engineer", "engineer_profile", "-x", "x-", "")) {
            assertThatThrownBy(() -> new ProductionPrivacyProviderRegistry(List.of(fake(id, "ELEMENT_X"))))
                    .as(id).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void 秘密を示す列をallow_listに置いたproviderは拒否する() {
        for (String column : List.of("password", "encrypted_totp_secret", "access_token_encrypted", "code_hash",
                "mfa_enabled", "dispatch_nonce_hash", "recovery_code")) {
            assertThatThrownBy(() -> new ProductionPrivacyProviderRegistry(List.of(
                    new FakeProvider("fake-provider", List.of(element("ELEMENT_S")),
                            List.of(new ProviderTable("t_fake", List.of("id", column), List.of()))))))
                    .as(column).isInstanceOf(IllegalStateException.class).hasMessageContaining("秘密");
        }
    }

    @Test
    void 未分類のdata_elementと2つのproviderが重なる宣言は拒否する() {
        assertThatThrownBy(() -> new ProductionPrivacyProviderRegistry(List.of(new FakeProvider("fake-provider",
                List.of(ProviderDataElement.exact("ELEMENT_U", SubjectAttribution.UNKNOWN, List.of())), table()))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("UNKNOWN");
        assertThatThrownBy(() -> new ProductionPrivacyProviderRegistry(List.of(new DocumentFileProvider(query),
                fake("other-document", "DOCUMENT:CONTRACT"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("2つの provider");
        assertThatThrownBy(() -> new ProductionPrivacyProviderRegistry(List.of(new FakeProvider("fake-provider",
                List.of(element("ELEMENT_D"), element("ELEMENT_D")), table()))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ProductionPrivacyProviderRegistry(List.of(new FakeProvider("fake-provider",
                List.of(), table()))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ProductionPrivacyProviderRegistry(List.of(new FakeProvider("fake-provider",
                List.of(element("ELEMENT_T")), List.of()))))
                .isInstanceOf(IllegalStateException.class);
    }

    private List<ProductionPrivacyProvider> realProviders() {
        return List.of(new SysUserProvider(query), new EngineerProfileProvider(query), new CandidateProvider(query),
                new DocumentFileProvider(query), new IngestionTextProvider(query), new AiLogProvider(query));
    }

    private static FakeProvider fake(String id, String elementId) {
        return new FakeProvider(id, List.of(element(elementId)), table());
    }

    private static ProviderDataElement element(String id) {
        return ProviderDataElement.exact(id, SubjectAttribution.ATTRIBUTABLE,
                List.of(new SubjectLink(PrivacySubjectType.ENGINEER, "t_fake.engineer_id")));
    }

    private static List<ProviderTable> table() {
        return List.of(new ProviderTable("t_fake", List.of("id", "engineer_id"), List.of()));
    }

    /** 検査用の provider（照会はしない）。 */
    record FakeProvider(String providerId, List<ProviderDataElement> dataElements, List<ProviderTable> tables)
            implements ProductionPrivacyProvider {
        @Override
        public List<PrivacyRecord> findBySubject(String tenantId, PrivacySubjectRef subject) {
            return List.of();
        }
    }
}
