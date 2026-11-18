package com.ses.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 本番 task definition の雛形が、初回起動（deploy-runbook §4・§4.2）に要る設定と、§1.0 の運用設定
 * （ClamAV・metrics scraper）を持つことの静的検査（Docker・AWS 不要）。
 *
 * <p>{@code validate-task-definition.ps1} は CI で動かないため、雛形から項目が落ちる回帰をここで止める。
 * 特に {@code SchedulingConfig} は {@code app.scheduling.enabled} の欠落を true とみなすため、
 * {@code APP_SCHEDULING_ENABLED=false} が雛形から消えると、初回起動で scheduler が動き出す（RB-DEPLOY、配備日確認表の #6）。
 */
class ProductionTaskDefinitionTemplateTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path TEMPLATE = Path.of("infra", "ecs", "task-definition.prod.json");
    private static final Path VALIDATOR = Path.of("infra", "ecs", "offline", "validate-task-definition.ps1");
    private static final Pattern FEATURE_SWITCH =
            Pattern.compile("^(FEATURE_NF[0-9A-Z_]*_ENABLED|PRIVACY_RETENTION_ENABLED|PRIVACY_DSAR_ENABLED)$");

    @Test
    void 雛形は初回起動をschedulerを止めたbootstrap_modeで行う設定を持つ() throws IOException {
        JsonNode app = appContainer();

        assertThat(environment(app, "APP_SCHEDULING_ENABLED")).as("初回は scheduler を止める（§4）").isEqualTo("false");
        assertThat(environment(app, "SECURITY_BOOTSTRAP_ENABLED")).as("初回は bootstrap mode（§4.2）").isEqualTo("true");
        assertThat(environment(app, "BREAK_GLASS_USERNAMES")).as("bootstrap 中は空（§4.2）").isEmpty();
        assertThat(names(app.path("environment")))
                .as("初回 bootstrap では再開 ticket と local login を設定しない（§4.2）")
                .doesNotContain("SECURITY_BOOTSTRAP_REOPEN_TICKET", "LOCAL_LOGIN_ENABLED");
        assertThat(names(app.path("environment")).stream().filter(name -> FEATURE_SWITCH.matcher(name).matches()))
                .as("初回リリースは機能スイッチの環境変数を設定しない（feature-switches 3.1）").isEmpty();
    }

    @Test
    void bootstrapの期限はplaceholderのままでは起動を通らない() throws IOException {
        String expiresAt = environment(appContainer(), "SECURITY_BOOTSTRAP_EXPIRES_AT");

        assertThat(expiresAt).as("期限は配備ごとに決めるため雛形には日時を書かない").isNotNull().contains("PLACEHOLDER");
        // ProductionBootstrapMode は OffsetDateTime.parse で読み、失敗すれば起動を止める（置換し忘れは fail-fast）
        assertThrows(DateTimeParseException.class, () -> OffsetDateTime.parse(expiresAt.trim()));
    }

    @Test
    void 雛形はClamAVとmetrics_scraperの設定を持ちpasswordはsecretだけに置く() throws IOException {
        JsonNode app = appContainer();

        assertThat(environment(app, "FILE_SCANNER_HOST")).as("ClamAV の host（置き方は §1.6 の6）").isNotBlank();
        assertThat(environment(app, "FILE_SCANNER_PORT")).isEqualTo("3310");
        assertThat(environment(app, "METRICS_SCRAPER_ENABLED")).isNotBlank();
        assertThat(environment(app, "METRICS_SCRAPER_USERNAME")).as("scraper 口座（SRE-9）").isNotBlank()
                .isNotEqualToIgnoringCase("admin");
        assertThat(names(app.path("secrets"))).contains("METRICS_SCRAPER_PASSWORD");
        assertThat(names(app.path("environment"))).doesNotContain("METRICS_SCRAPER_PASSWORD");
    }

    @Test
    void APP_SCHEDULING_ENABLEDはschedulerの条件propertyへ束縛される() {
        SystemEnvironmentPropertySource env = new SystemEnvironmentPropertySource("env",
                Map.of("APP_SCHEDULING_ENABLED", "false"));

        // SchedulingConfig の @ConditionalOnProperty(name = "app.scheduling.enabled", matchIfMissing = true)
        assertThat(env.getProperty("app.scheduling.enabled")).isEqualTo("false");
    }

    @Test
    void validatorは雛形に足した項目を検査する() throws IOException {
        String validator = Files.readString(VALIDATOR, StandardCharsets.UTF_8);

        for (String name : List.of("APP_SCHEDULING_ENABLED", "SECURITY_BOOTSTRAP_ENABLED", "SECURITY_BOOTSTRAP_EXPIRES_AT",
                "SECURITY_BOOTSTRAP_REOPEN_TICKET", "FILE_SCANNER_HOST", "FILE_SCANNER_PORT", "METRICS_SCRAPER_ENABLED",
                "METRICS_SCRAPER_USERNAME", "METRICS_SCRAPER_PASSWORD")) {
            assertThat(validator).as("validate-task-definition.ps1 が %s を検査する", name).contains("'" + name + "'");
        }
        // 実値を入れた定義の検査と、repository の外への証跡出力（RB-DEPLOY、配備日確認表の #9）
        assertThat(validator).contains("ValidateSet('Template', 'Actual')").contains("ValidateSet('Initial', 'Steady')")
                .contains("GetTempPath()").doesNotContain(".kiro\\reviews\\production-acceptance");
    }

    private static JsonNode appContainer() throws IOException {
        JsonNode taskDefinition = JSON.readTree(Files.readString(TEMPLATE, StandardCharsets.UTF_8));
        for (JsonNode container : taskDefinition.path("containerDefinitions")) {
            if ("ses-manager".equals(container.path("name").asText())) {
                return container;
            }
        }
        throw new AssertionError("container ses-manager が task definition に無い");
    }

    private static String environment(JsonNode container, String name) {
        for (JsonNode entry : container.path("environment")) {
            if (name.equals(entry.path("name").asText())) {
                return entry.path("value").asText();
            }
        }
        return null;
    }

    private static List<String> names(JsonNode entries) {
        List<String> names = new ArrayList<>();
        entries.forEach(entry -> names.add(entry.path("name").asText()));
        return names;
    }
}
