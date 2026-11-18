package com.ses.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.util.ClientIpResolver;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * FWD-SANITIZE P2-1: container health checkがHTTP 200だけを健康と判定することの静的検査（Docker不要）。
 *
 * <p>本番はHTTPS強制で、task内の127.0.0.1から送る {@code X-Forwarded-Proto} は信頼しない（loopbackは信頼プロキシに
 * しない）。{@code curl -f} は302を成功と扱うため、HTTPS強制のredirectやXFP前提の書き方だと
 * LivenessState=BROKEN（503）を検知できず、taskが常にhealthyに見える。health probe 3pathはHTTPのまま200/503を
 * 返すので、health checkは {@code %{http_code}} を取り出して200と比較し、転送ヘッダーを送らないこと。
 */
class ContainerHealthCheckCommandGuardTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern COMPARES_200 = Pattern.compile("(?:=|-eq)\\s*\"?200\"?(?![0-9])|grep\\s+-qx\\s+200");
    private static final String APP_CONTAINER = "ses-manager";

    @Test
    void 本番task定義のhealthCheckは200だけを成功にする() throws IOException {
        JsonNode taskDefinition = readJson(Path.of("infra", "ecs", "task-definition.prod.json"));
        List<JsonNode> apps = new ArrayList<>();
        for (JsonNode container : taskDefinition.path("containerDefinitions")) {
            if (APP_CONTAINER.equals(container.path("name").asText())) {
                apps.add(container);
            }
        }

        assertThat(apps).as("app の container（%s）はちょうど1つ", APP_CONTAINER).hasSize(1);
        assertStrict200(command(apps.get(0)), "task-definition.prod.json");
    }

    /**
     * app 以外の container（ClamAV の sidecar。deploy-runbook §1.6 の6）は app の actuator を見ない自前の health check を持ち、
     * essential にしない。essential にすると clamd の停止で task ごと止まるが、upload は scanner に届かなければ quarantine に
     * 留まる（fail-closed）ため、app を止める理由が無い。
     */
    @Test
    void 本番task定義のsidecarは自前のhealthCheckを持ちessentialにしない() throws IOException {
        JsonNode taskDefinition = readJson(Path.of("infra", "ecs", "task-definition.prod.json"));
        List<String> sidecars = new ArrayList<>();
        for (JsonNode container : taskDefinition.path("containerDefinitions")) {
            String name = container.path("name").asText();
            if (APP_CONTAINER.equals(name)) {
                continue;
            }
            sidecars.add(name);
            assertThat(container.path("essential").isBoolean() && !container.path("essential").asBoolean())
                    .as("%s は essential=false を明示する（ECS は省略を true とみなす）", name).isTrue();
            assertThat(command(container)).as("%s の health check は app の actuator を使わない", name)
                    .doesNotContain("/actuator/").doesNotContain("X-Forwarded");
        }
        assertThat(sidecars).as("task の sidecar は ClamAV だけ（validate-task-definition.ps1 と同じ）").containsExactly("clamav");
    }

    @Test
    void 本番task定義はTRUSTED_PROXIESを持ち置換前のままでは起動しない() throws IOException {
        JsonNode taskDefinition = readJson(Path.of("infra", "ecs", "task-definition.prod.json"));
        String value = environment(taskDefinition.path("containerDefinitions").get(0), "TRUSTED_PROXIES");

        assertThat(value).as("TRUSTED_PROXIESのplaceholder").isNotNull().doesNotContain("127.");
        // 置換し忘れたまま配備すると、構文検査でapplicationが起動しない（fail-fast）
        assertThrows(IllegalStateException.class, () -> new ClientIpResolver(value));
    }

    @Test
    void local検証用task定義もhealthCheckは200だけを成功にする() throws IOException {
        JsonNode taskDefinition = readJson(Path.of("infra", "local-aws", "ecs", "task-definition.local.json"));

        for (JsonNode container : taskDefinition.path("containerDefinitions")) {
            assertStrict200(command(container), "task-definition.local.json");
        }
        assertThat(environment(taskDefinition.path("containerDefinitions").get(0), "TRUSTED_PROXIES")).isNotNull();
    }

    @Test
    void DockerfileのHEALTHCHECKは200だけを成功にする() throws IOException {
        List<String> lines = Files.readAllLines(Path.of("Dockerfile"), StandardCharsets.UTF_8);
        StringBuilder healthcheck = new StringBuilder();
        boolean inside = false;
        for (String line : lines) {
            if (line.startsWith("HEALTHCHECK")) {
                inside = true;
            }
            if (inside) {
                healthcheck.append(line.endsWith("\\") ? line.substring(0, line.length() - 1) : line).append(' ');
                if (!line.endsWith("\\")) {
                    break;
                }
            }
        }

        assertThat(healthcheck).as("DockerfileにHEALTHCHECKがあること").isNotEmpty();
        assertStrict200(healthcheck.toString(), "Dockerfile");
    }

    @Test
    void offline検証composeのhealthcheckは200だけを成功にする() throws IOException {
        List<String> commands = Files.readAllLines(Path.of("infra", "ecs", "offline", "docker-compose.yml"),
                        StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> line.startsWith("test:") && line.contains("/actuator/health"))
                .toList();

        assertThat(commands).as("composeのhealthcheck").isNotEmpty();
        for (String command : commands) {
            assertStrict200(command, "docker-compose.yml");
        }
    }

    @Test
    void 検査規則は302を成功にする従来の書き方を拒否する() {
        for (String legacy : List.of(
                "curl -fsS -H \"X-Forwarded-Proto: https\" http://127.0.0.1:8080/actuator/health/liveness >/dev/null || exit 1",
                "curl -fsS http://127.0.0.1:8080/actuator/health/liveness >/dev/null || exit 1",
                "curl -f http://localhost:8080/actuator/health/liveness || exit 1",
                "curl -sS -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health/liveness | grep -q 2")) {
            assertThat(problems(legacy)).as(legacy).isNotEmpty();
        }
        assertThat(problems("test \"$(curl -sS -o /dev/null -m 4 -w '%{http_code}' "
                + "http://127.0.0.1:8080/actuator/health/liveness)\" = 200 || exit 1")).isEmpty();
    }

    private static void assertStrict200(String command, String source) {
        assertThat(problems(command)).as("%s: %s", source, command).isEmpty();
    }

    static List<String> problems(String command) {
        List<String> problems = new ArrayList<>();
        if (!command.contains("/actuator/health/liveness")) {
            problems.add("container health checkはlivenessを使うこと");
        }
        if (command.contains("X-Forwarded")) {
            problems.add("転送ヘッダーを送らないこと（loopbackは信頼プロキシにしない）");
        }
        if (!command.contains("%{http_code}")) {
            problems.add("curl -fは302を成功と扱うため、%{http_code}で状態を取り出すこと");
        }
        if (!COMPARES_200.matcher(command).find()) {
            problems.add("取り出した状態を200と比較すること");
        }
        return problems;
    }

    private static JsonNode readJson(Path path) throws IOException {
        return JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
    }

    private static String command(JsonNode container) {
        List<String> parts = new ArrayList<>();
        container.path("healthCheck").path("command").forEach(part -> parts.add(part.asText()));
        assertThat(parts).as("healthCheck.command").isNotEmpty();
        return String.join(" ", parts);
    }

    private static String environment(JsonNode container, String name) {
        for (JsonNode entry : container.path("environment")) {
            if (name.equals(entry.path("name").asText())) {
                return entry.path("value").asText();
            }
        }
        return null;
    }
}
