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
    private static final Path SELF_TEST = Path.of("infra", "ecs", "offline", "test-validate-task-definition.ps1");
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
                .as("初回の起動（bootstrap）は機能スイッチの環境変数を設定しない。初回リリースで ON にするスイッチは §5.5 の後の起動で実値の"
                        + " task definition に設定する（feature-switches 3.1）").isEmpty();
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

        assertThat(environment(app, "FILE_SCANNER_HOST")).as("ClamAV は同じ task の sidecar（§1.6 の6。2026-11-18 決定）")
                .isEqualTo("127.0.0.1");
        assertThat(environment(app, "FILE_SCANNER_PORT")).isEqualTo("3310");
        assertThat(environment(app, "METRICS_SCRAPER_ENABLED")).as("ALB 経由の HTTPS で scrape する（§1.6 の7。2026-11-18 決定）")
                .isEqualTo("true");
        assertThat(environment(app, "METRICS_SCRAPER_USERNAME")).as("scraper 口座（SRE-9）").isNotBlank()
                .isNotEqualToIgnoringCase("admin");
        assertThat(names(app.path("secrets"))).contains("METRICS_SCRAPER_PASSWORD");
        assertThat(names(app.path("environment"))).doesNotContain("METRICS_SCRAPER_PASSWORD");
    }

    /**
     * ClamAV の sidecar を足しても app の JVM の大きさが変わらないこと。app に container の hard limit が無いと、JVM
     * （MaxRAMPercentage=75）は task 全体の memory を基準にし、clamd の分まで heap に取る。
     */
    @Test
    void 雛形のtaskはappとClamAVの分の大きさを持ちappにhard_limitを置く() throws IOException {
        JsonNode taskDefinition = taskDefinition();
        JsonNode app = appContainer();
        JsonNode scanner = container("clamav");

        assertThat(taskDefinition.path("cpu").asText()).isEqualTo("2048");
        assertThat(taskDefinition.path("memory").asText()).isEqualTo("6144");
        assertThat(app.path("cpu").asInt()).isEqualTo(1024);
        assertThat(app.path("memory").asInt()).as("app の hard limit（従来の task と同じ 2048）").isEqualTo(2048);
        assertThat(scanner.path("memory").asInt()).isPositive();
        assertThat(app.path("memory").asInt() + scanner.path("memory").asInt())
                .as("container の memory の合計は task の memory 以内").isLessThanOrEqualTo(taskDefinition.path("memory").asInt());
        assertThat(scanner.path("image").asText()).as("ClamAV の image も registry digest で固定する").endsWith("@sha256:CLAMAV_IMAGE_DIGEST");
    }

    /**
     * ClamAV の sidecar は信頼できない upload を扱うため、root filesystem を読取り専用にし、自分の bind volume だけを
     * mount し、secret も app の設定も受け取らず、port を公開しない（RB-DEPLOY 差分復審 P3）。
     */
    @Test
    void 雛形のClamAV_sidecarは読取り専用で自分のvolumeだけを持ちsecretを受け取らない() throws IOException {
        JsonNode taskDefinition = taskDefinition();
        JsonNode scanner = container("clamav");
        List<String> bindVolumes = new ArrayList<>();
        for (JsonNode volume : taskDefinition.path("volumes")) {
            if (volume.path("efsVolumeConfiguration").isMissingNode()) {
                bindVolumes.add(volume.path("name").asText());
            }
        }

        assertThat(scanner.path("readonlyRootFilesystem").asBoolean()).isTrue();
        assertThat(scanner.path("mountPoints")).as("signature・run・log・tmp の書込み先").isNotEmpty();
        for (JsonNode mount : scanner.path("mountPoints")) {
            String volume = mount.path("sourceVolume").asText();
            assertThat(volume).as("clamav は自分の bind volume だけを mount する").startsWith("clamav-");
            assertThat(bindVolumes).as("%s は EFS ではない bind volume", volume).contains(volume);
        }
        assertThat(scanner.path("secrets").isMissingNode() || scanner.path("secrets").isEmpty()).as("secret を渡さない").isTrue();
        assertThat(scanner.path("environment").isMissingNode() || scanner.path("environment").isEmpty())
                .as("app の設定を渡さない").isTrue();
        assertThat(scanner.path("portMappings").isMissingNode() || scanner.path("portMappings").isEmpty())
                .as("port を公開しない（app は 127.0.0.1 で clamd に届く）").isTrue();
    }

    /**
     * NF07 の trust 文書は EFS に置き、app は読取りだけで mount する（§1.6 の11。2026-11-18 決定）。第1期は file を指さない。
     * EFS の file system policy は task role で許すため、uploads と trust の volume は IAM 認可で mount する。
     */
    @Test
    void 雛形はNF07_trust文書のEFSを読取り専用でmountし第1期はfileを指さない() throws IOException {
        JsonNode taskDefinition = taskDefinition();
        JsonNode app = appContainer();
        JsonNode trustMount = null;
        for (JsonNode mount : app.path("mountPoints")) {
            if ("privacy-trust".equals(mount.path("sourceVolume").asText())) {
                trustMount = mount;
            }
        }
        String uploadsAccessPoint = null;
        String trustAccessPoint = null;
        for (JsonNode volume : taskDefinition.path("volumes")) {
            String accessPoint = volume.path("efsVolumeConfiguration").path("authorizationConfig").path("accessPointId").asText();
            if (!volume.path("efsVolumeConfiguration").isMissingNode()) {
                assertThat(volume.path("efsVolumeConfiguration").path("authorizationConfig").path("iam").asText())
                        .as("%s は IAM 認可で mount する（file system policy は task role で許す）", volume.path("name").asText())
                        .isEqualTo("ENABLED");
            }
            if ("uploads".equals(volume.path("name").asText())) {
                uploadsAccessPoint = accessPoint;
            } else if ("privacy-trust".equals(volume.path("name").asText())) {
                trustAccessPoint = accessPoint;
                assertThat(volume.path("efsVolumeConfiguration").path("transitEncryption").asText()).isEqualTo("ENABLED");
            }
        }

        assertThat(trustMount).as("privacy-trust の mount").isNotNull();
        assertThat(trustMount.path("readOnly").asBoolean()).as("app は trust 文書を書かない").isTrue();
        assertThat(trustAccessPoint).as("uploads とは別の access point").isNotBlank().isNotEqualTo(uploadsAccessPoint);
        assertThat(environment(app, "PRIVACY_TRUST_KMS_ANCHOR_FILE")).as("第1期は空（trust は anchor 0件のまま）").isEmpty();
    }

    /**
     * upload root は uploads の mount の下の相対 symlink {@code current} にする（§1.6 の11 (f)。2026-11-18 決定 A）。
     * restore の後、停止中に1回の rename で版を切り替えるため。値が無いと app は mount root を直接使い、pointer が無くなる。
     */
    @Test
    void 雛形はupload_rootをuploadsのmountの下のcurrent_symlinkにする() throws IOException {
        JsonNode app = appContainer();
        String uploadsPath = null;
        for (JsonNode mount : app.path("mountPoints")) {
            if ("uploads".equals(mount.path("sourceVolume").asText())) {
                uploadsPath = mount.path("containerPath").asText();
            }
        }

        assertThat(environment(app, "UPLOAD_BASE_PATH")).isEqualTo("/app/uploads/current");
        assertThat(uploadsPath).as("uploads の mount").isEqualTo("/app/uploads");
        assertThat(names(app.path("secrets"))).doesNotContain("UPLOAD_BASE_PATH");
    }

    /**
     * backup の書込み静止（RB-QUIESCE BWQ-007。deploy-runbook §1.0 の31〜41、design.md §12.3）: 雛形は静止を有効と明示し、停止口の
     * ticket を持たない。起動の待ち（{@code startup-wait-max} 既定 60 秒）の間に health check で殺されないよう
     * {@code startPeriod >= 60 + 120}、停止の排水（graceful 30 秒＋executor 30 秒＋余裕）のため {@code stopTimeout >= 90}。
     */
    @Test
    void 雛形はbackupの書込み静止を有効と明示しstopTimeoutとstartPeriodを持つ() throws IOException {
        JsonNode app = appContainer();

        assertThat(environment(app, "BACKUP_QUIESCE_ENABLED")).as("雛形は true を明示する（U-7 の停止口は実値の mode だけ）")
                .isEqualTo("true");
        assertThat(names(app.path("environment"))).doesNotContain("BACKUP_QUIESCE_DISABLED_TICKET");
        assertThat(names(app.path("secrets")).stream().filter(name -> name.startsWith("BACKUP_QUIESCE_"))).isEmpty();
        assertThat(environment(app, "SHUTDOWN_TIMEOUT")).isEqualTo("30s");
        assertThat(app.path("stopTimeout").asInt()).as("SHUTDOWN_TIMEOUT 30 秒＋executor 30 秒＋余裕。Fargate の上限は 120")
                .isGreaterThanOrEqualTo(90).isLessThanOrEqualTo(120);
        assertThat(app.path("healthCheck").path("startPeriod").asInt()).as("startup-wait-max（60 秒）＋120 秒")
                .isGreaterThanOrEqualTo(60 + 120);
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
                "METRICS_SCRAPER_USERNAME", "METRICS_SCRAPER_PASSWORD", "PRIVACY_TRUST_KMS_ANCHOR_FILE", "clamav",
                "privacy-trust", "JAVA_TOOL_OPTIONS", "SPRING_APPLICATION_JSON", "SPRING_PROFILES_ACTIVE", "entryPoint",
                "management", "UPLOAD_BASE_PATH", "/app/uploads/current", "BACKUP_QUIESCE_ENABLED",
                "BACKUP_QUIESCE_DISABLED_TICKET", "BACKUP_QUIESCE_STARTUP_WAIT_MAX", "SHUTDOWN_TIMEOUT", "APPBACKUPQUIESCE",
                "app.backupquiesce")) {
            assertThat(validator).as("validate-task-definition.ps1 が %s を検査する", name).contains("'" + name + "'");
        }
        // RB-QUIESCE BWQ-007: 停止口の ticket の形式、startPeriod と stopTimeout の下限（design.md §12.3）
        assertThat(validator).contains("^[A-Z][A-Z0-9]+-[0-9]+$").contains("$c.stopTimeout")
                .contains("$c.healthCheck.startPeriod");
        // 実値を入れた定義の検査と、repository の外への証跡出力（RB-DEPLOY、配備日確認表の #9）。
        // §5.5 以後は scheduler の期待値を必ず指定させる（RB-DEPLOY 復審 P2-1）
        assertThat(validator).contains("ValidateSet('Template', 'Actual')").contains("ValidateSet('Initial', 'Steady')")
                .contains("ValidateSet('Enabled', 'Disabled')").contains("-Phase Steady needs -Scheduler")
                .contains("GetTempPath()").doesNotContain(".kiro\\reviews\\production-acceptance");
        // RB-DEPLOY 第3次差分復審 P3: profile の大文字小文字、volumesFrom、file から読む JVM option、EFS の IAM 認可
        assertThat(validator).contains("-cne 'prod'").contains("volumesFrom").contains("VMOptionsFile")
                .contains("authorizationConfig.iam");
        // 規則ごとの正例・負例（CI では動かないため、手で流す script が repository にあることだけを見る）
        assertThat(SELF_TEST).exists();
        assertThat(Files.readString(SELF_TEST, StandardCharsets.UTF_8)).contains(VALIDATOR.getFileName().toString());
    }

    /**
     * validator と self-test は Windows PowerShell 5.1 でも動かす。5.1 は BOM の無い file を ANSI の code page で読むため、
     * 日本語を書くと出力と証跡が文字化けする。2つの script は ASCII だけで書く（{@code infra/ecs/offline/README.md}）。
     */
    @Test
    void validatorとself_testはASCIIだけで書く() throws IOException {
        for (Path script : List.of(VALIDATOR, SELF_TEST)) {
            byte[] bytes = Files.readAllBytes(script);
            List<Integer> nonAscii = new ArrayList<>();
            for (int i = 0; i < bytes.length && nonAscii.size() < 5; i++) {
                if ((bytes[i] & 0x80) != 0) {
                    nonAscii.add(i);
                }
            }
            assertThat(nonAscii).as("%s の ASCII でない byte の位置", script).isEmpty();
        }
    }

    private static JsonNode taskDefinition() throws IOException {
        return JSON.readTree(Files.readString(TEMPLATE, StandardCharsets.UTF_8));
    }

    private static JsonNode appContainer() throws IOException {
        return container("ses-manager");
    }

    private static JsonNode container(String name) throws IOException {
        for (JsonNode container : taskDefinition().path("containerDefinitions")) {
            if (name.equals(container.path("name").asText())) {
                return container;
            }
        }
        throw new AssertionError("container " + name + " が task definition に無い");
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
