package com.ses.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FE-D03（feature-enablement-console design §9.3・review P3-15）: NF05 の開閉の flag（{@code getPublicApi().getEnabled()}・
 * {@code getExternalTransport().getEnabled()}）は起動時の値で、実行時の判定は {@code IntegrationHubRuntimeSwitch}（起動時の値 AND
 * 実効値）だけが読む。flag を直接読めるのは、{@code @Conditional} の条件（property 名を Binder で読む）・起動時の検査・runtime switch・
 * property の源の開閉の値だけに固定する（file ごとの件数）。承認の値（決定 ID・digest）の getter は {@code IntegrationHubApprovals}
 * だけが呼ぶ（DB の源では properties の決定 ID が空のため）。
 */
class IntegrationHubFlagReadGuardTest {

    private static final Path MAIN = Path.of("src", "main", "java");
    private static final Pattern FLAG_READ = Pattern.compile(
            "getPublicApi\\(\\)\\s*\\.\\s*getEnabled\\(\\)|getExternalTransport\\(\\)\\s*\\.\\s*getEnabled\\(\\)");
    private static final Pattern APPROVAL_READ = Pattern.compile("\\.getDecisionId\\(\\)|\\.getApprovalDigest\\(\\)");
    private static final Pattern NF05_APPROVAL_TYPE = Pattern.compile(
            "InboundApproval|OutboundApproval|getPublicApi\\(\\)\\s*\\.\\s*getApproval\\(\\)"
                    + "|getExternalTransport\\(\\)\\s*\\.\\s*getApproval\\(\\)");

    /** flag を直接読んでよい file と件数（理由）。 */
    private static final Map<String, Integer> ALLOWED_FLAG_READS = Map.of(
            // 実行時の判定の唯一の入口（起動時の値の読取り）
            "com/ses/featureenablement/nf05/IntegrationHubRuntimeSwitch.java", 2,
            // property の源の開閉の値（{@code FeatureSwitches} の property の源。DB の源では使わない）
            "com/ses/config/feature/FeatureSwitchProperties.java", 2,
            // 起動時の検査（property の源の承認の照合）
            "com/ses/config/integrationhub/IntegrationHubProductionEnablementPolicy.java", 2,
            // 起動時の connector の構成（Tomcat の connector は起動の時に1回だけ作る）
            "com/ses/config/integrationhub/ExternalApiTomcatConfiguration.java", 2,
            // 起動時の検査（ApplicationReadyEvent の法人の未解決。property の源の NF05 と copilot）
            "com/ses/config/LegalEntityReadinessStartupValidator.java", 2);

    @Test
    void NF05のflagを直接読むのは起動時の検査とruntime_switchとpropertyの源だけ() throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        for (Path file : javaFiles()) {
            int count = count(FLAG_READ, read(file));
            if (count > 0) {
                found.put(relative(file), count);
            }
        }
        assertThat(found).as("実行時の NF05 の判定は IntegrationHubRuntimeSwitch を通す（design §9.3）")
                .isEqualTo(new TreeMap<>(ALLOWED_FLAG_READS));
    }

    @Test
    void NF05の承認の値のgetterを呼ぶのはIntegrationHubApprovalsだけ() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String source = read(file);
            if (!NF05_APPROVAL_TYPE.matcher(source).find() || !APPROVAL_READ.matcher(source).find()) {
                continue;
            }
            String name = relative(file);
            if (!name.equals("com/ses/config/integrationhub/IntegrationHubApprovals.java")) {
                violations.add(name);
            }
        }
        assertThat(violations).as("承認の値は IntegrationHubApprovals を通す（review P3-15）").isEmpty();
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static String relative(Path file) {
        return MAIN.relativize(file).toString().replace('\\', '/');
    }

    private static int count(Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
