package com.ses.featureenablement.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.config.feature.Feature;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FE-E01（feature-enablement-console design §10・§16、review rb-fe-int P3-5）:
 * <ul>
 *   <li>Java の {@link FeatureEnablementProductionValidator} の旧い開閉変数（全 13 件の {@link Feature} の環境変数名・property 名）と
 *       署名・設定へ移した承認値の接頭辞（{@link FeatureEnablementProductionValidator#LEGACY_SETTING_RULES}）が、
 *       {@code infra/ecs/offline/validate-task-definition.ps1} の {@code $legacySwitchKeys}・{@code $legacySettingPrefixes} と
 *       完全に一致すること。</li>
 *   <li>本番 task definition の雛形（{@code infra/ecs/task-definition.prod.json}）の {@code environment}・{@code secrets} の全変数名、
 *       および配備の設定に残る運用値・基盤設定の名前を {@link FeatureEnablementProductionValidator} に渡しても違反が出ないこと。</li>
 * </ul>
 */
class FeatureEnablementLegacySettingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path VALIDATOR_PS1 = Path.of("infra", "ecs", "offline", "validate-task-definition.ps1");
    private static final Path TEMPLATE_JSON = Path.of("infra", "ecs", "task-definition.prod.json");

    @Test
    void Javaの旧い変数一覧とps1の配列が完全に一致し全13開放単位を網羅する() throws IOException {
        FeatureEnablementProperties properties = new FeatureEnablementProperties();
        properties.setSource(FeatureEnablementProperties.Source.DATABASE);
        MockEnvironment prod = prodEnvironment();
        FeatureEnablementProductionValidator validator = new FeatureEnablementProductionValidator(prod, properties,
                FeatureEnablementCatalog.standard(), Map::of, Properties::new);

        String ps1 = Files.readString(VALIDATOR_PS1, StandardCharsets.UTF_8);
        Set<String> ps1SwitchKeys = parsePs1StringArray(ps1, "legacySwitchKeys");
        Set<String> ps1SettingPrefixes = parsePs1StringArray(ps1, "legacySettingPrefixes");

        assertThat(ps1SwitchKeys).as("$legacySwitchKeys と validator.legacyKeys() の一致")
                .containsExactlyInAnyOrderElementsOf(validator.legacyKeys());
        assertThat(ps1SettingPrefixes).as("$legacySettingPrefixes と validator.legacySettingPrefixes() の一致")
                .containsExactlyInAnyOrderElementsOf(validator.legacySettingPrefixes());

        for (Feature feature : Feature.values()) {
            assertThat(ps1SwitchKeys).contains(
                    FeatureEnablementProductionValidator.nameKey(feature.environmentVariable()),
                    FeatureEnablementProductionValidator.nameKey(feature.propertyName()));
            assertThatThrownBy(() -> new FeatureEnablementProductionValidator(prodEnvironment(), properties,
                    FeatureEnablementCatalog.standard(), () -> Map.of(feature.environmentVariable(), "false"),
                    Properties::new).validate())
                    .as("%s の環境変数は値に依らず拒否する", feature)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(feature.environmentVariable());
        }
    }

    @Test
    void task_definition雛形の全ての設定名と配備に残る運用値はvalidatorを通過する() throws IOException {
        JsonNode root = JSON.readTree(Files.readString(TEMPLATE_JSON, StandardCharsets.UTF_8));
        JsonNode app = null;
        for (JsonNode container : root.path("containerDefinitions")) {
            if ("ses-manager".equals(container.path("name").asText())) {
                app = container;
                break;
            }
        }
        assertThat(app).as("ses-manager container").isNotNull();

        Map<String, String> env = new LinkedHashMap<>();
        for (JsonNode entry : app.path("environment")) {
            String name = entry.path("name").asText();
            String value = entry.path("value").asText();
            env.put(name, FeatureEnablementProductionValidator.ALLOWED_VARIABLE.equals(name) ? value : "sample");
        }
        for (JsonNode entry : app.path("secrets")) {
            env.put(entry.path("name").asText(), "secret-placeholder");
        }
        // 配備の設定に残る運用値・基盤設定・freee の4項目（deploy-runbook §1.0）も合わせて確かめる
        for (String operational : List.of(
                "FREEE_CLIENT_ID", "FREEE_CLIENT_SECRET", "FREEE_REDIRECT_URI", "FREEE_TOKEN_ENCRYPTION_KEY",
                "PRIVACY_DSAR_DEADLINE_WARNING_DAYS",
                "PRIVACY_JOURNAL_DATASOURCE_URL", "PRIVACY_JOURNAL_DATASOURCE_USERNAME",
                "PRIVACY_JOURNAL_DATASOURCE_PASSWORD", "PRIVACY_JOURNAL_EXPECTED_SCHEMA", "PRIVACY_JOURNAL_JOURNAL_ID",
                "PRIVACY_JOURNAL_PENDING_TIMEOUT", "PRIVACY_CLOCK_MAX_SKEW",
                "PRIVACY_TRUST_KMS_MAX_STALENESS", "PRIVACY_TRUST_KMS_REFRESH_INTERVAL",
                "IMPORT_ROW_REFERENCE_HMAC_KEY",
                "INTEGRATION_HUB_BOUND_TENANT_ID", "INTEGRATION_HUB_PUBLIC_ID_KEY",
                "INTEGRATION_HUB_SECURITY_TRUSTED_PROXIES",
                "INTEGRATION_HUB_PUBLIC_API_APPROVAL_ALLOWED_CLIENT_CIDRS",
                "INTEGRATION_HUB_EXTERNAL_TRANSPORT_APPROVAL_ALLOWED_DESTINATIONS",
                "INTEGRATION_HUB_TRUSTSTORE_PATH", "INTEGRATION_HUB_TRUSTSTORE_PASSWORD",
                "INTEGRATION_HUB_CRYPTO_CURRENT_KEY_VERSION", "INTEGRATION_HUB_CRYPTO_KEYS_V1",
                "INTEGRATION_HUB_CRYPTO_CREDENTIAL_HASH_PEPPER")) {
            env.put(operational, "sample");
        }

        FeatureEnablementProperties properties = new FeatureEnablementProperties();
        properties.setSource(FeatureEnablementProperties.Source.DATABASE);
        MockEnvironment prod = prodEnvironment();
        prod.setProperty(FeatureEnablementProductionValidator.ALLOWED_PROPERTY,
                env.get(FeatureEnablementProductionValidator.ALLOWED_VARIABLE));

        assertThatCode(() -> new FeatureEnablementProductionValidator(prod, properties,
                FeatureEnablementCatalog.standard(), () -> env, Properties::new).validate())
                .doesNotThrowAnyException();
    }

    private static MockEnvironment prodEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty(FeatureEnablementProductionValidator.ALLOWED_PROPERTY, "true");
        return environment;
    }

    private static Set<String> parsePs1StringArray(String ps1, String variableName) {
        Pattern blockPattern = Pattern.compile("\\$" + Pattern.quote(variableName) + "\\s*=\\s*@\\((.*?)\\)",
                Pattern.DOTALL);
        Matcher block = blockPattern.matcher(ps1);
        assertThat(block.find()).as("ps1 の $" + variableName + " 配列").isTrue();
        Set<String> items = new LinkedHashSet<>();
        Matcher item = Pattern.compile("'([^']+)'").matcher(block.group(1));
        while (item.find()) {
            items.add(item.group(1));
        }
        assertThat(items).isNotEmpty();
        return items;
    }
}
