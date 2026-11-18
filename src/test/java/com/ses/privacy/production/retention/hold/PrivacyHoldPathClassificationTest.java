package com.ses.privacy.production.retention.hold;

import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import com.ses.service.FileReferenceProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NF07 A1H-2 C-2 の分類 test: gate に接続した経路（{@link PrivacyHoldPath}）から逆に、各接続点が実際に gate を呼ぶこと（source に
 * 経路の定数がある、D10 は {@code FileReferenceProvider} として登録される）と、経路が判定する data element が catalog の seed
 * （V196・V197。H2 の testdb で実行済み）で UNKNOWN でないこと（provider の宣言と照合した実効の分類。C-2-2）を固定する。
 * UNKNOWN の element は hold 機構を使った tenant で経路を止め続けるため、接続点を足す batch は seed も合わせる。
 * NO_PERSONAL_DATA の経路（D8 の nonce）は接続しない（§3.4.3 規則2）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PrivacyHoldPathClassificationTest {

    /** seed の対象 tenant（V196 が sys_user の tenant から入れる）。 */
    private static final String SEEDED_TENANT = "default";

    @Autowired
    private HoldSubjectResolver resolver;
    @Autowired
    private List<FileReferenceProvider> fileReferenceProviders;

    @Test
    void 接続した経路のdata_elementはseedのcatalogで分類済みでUNKNOWNでも個人データなしでもない() {
        Map<String, SubjectAttribution> catalog = resolver.catalog(SEEDED_TENANT);

        for (PrivacyHoldPath path : PrivacyHoldPath.values()) {
            for (String element : path.dataElements()) {
                assertThat(catalog).as(path + " " + element + " が catalog にある").containsKey(element);
                assertThat(catalog.get(element)).as(path + " " + element + " の実効の分類")
                        .isIn(SubjectAttribution.ATTRIBUTABLE, SubjectAttribution.NOT_ATTRIBUTABLE);
            }
        }
    }

    @Test
    void 各経路の接続点はgateを経路の定数で呼びD10はFileReferenceProviderとして登録される() throws IOException {
        for (PrivacyHoldPath path : PrivacyHoldPath.values()) {
            Path source = Path.of("src/main/java", path.connectionPoint());
            assertThat(source).as(path + " の接続点").exists();
            if (path == PrivacyHoldPath.D10) {
                continue;
            }
            assertThat(Files.readString(source)).as(path + " の接続点が gate を呼ぶ")
                    .contains("PrivacyHoldPath." + path.name());
        }
        assertThat(fileReferenceProviders).anyMatch(PrivacyHoldFileReferenceProvider.class::isInstance);
    }

    @Test
    void D8は個人情報を含まないため経路として宣言せずnonceのpurgeはgateを通らない() throws IOException {
        assertThat(Arrays.stream(PrivacyHoldPath.values()).map(Enum::name)).doesNotContain("D8");
        String scheduler = Files.readString(Path.of("src/main/java", PrivacyHoldPath.D7.connectionPoint()));
        String afterRecords = scheduler.substring(scheduler.indexOf("hold.close()"));
        assertThat(afterRecords).as("nonce の purge は record の判定の後に hold と無関係に呼ぶ")
                .contains("nonceReplayService.purgeExpired(now, batchSize)");
    }

    @Test
    void 文書の経路は全ての文書種別を判定しD7はschedulerの全kindを判定する() {
        assertThat(PrivacyHoldPath.D1.dataElements()).hasSize(
                com.ses.privacy.production.provider.DocumentFileProvider.DOCUMENT_TYPE_RULES.size());
        assertThat(PrivacyHoldPath.D7.dataElements()).containsExactly("INTEGRATION_HUB_IDEMPOTENCY",
                "INTEGRATION_HUB_DELIVERY", "INTEGRATION_HUB_INBOUND", "INTEGRATION_HUB_INBOUND_REPLAY",
                "INTEGRATION_HUB_AUDIT", "INTEGRATION_HUB_EXTERNAL_AUDIT");
    }
}
