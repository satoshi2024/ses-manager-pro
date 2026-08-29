package com.ses.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Comprehensive Secret Scan Test (DDL・Entity・DTO・HTML・JS・Log・Exception 秘密非保存全方位スキャン)")
class AssetComprehensiveSecretScanTest {

    private static final List<String> FORBIDDEN_KEYWORDS = List.of(
            "password", "passwd", "secret", "token", "recovery_code", "credential", "private_key", "api_key"
    );

    @Test
    @DisplayName("1. Scan Entity and DTO fields for forbidden secret keywords")
    void scanEntityAndDtoFields() throws ClassNotFoundException {
        List<String> assetClasses = List.of(
                "com.ses.entity.Asset",
                "com.ses.entity.AssetAssignment",
                "com.ses.entity.AssetEvent",
                "com.ses.entity.AssetInventoryRun",
                "com.ses.entity.AssetInventoryItem",
                "com.ses.entity.ExternalAccountSystem",
                "com.ses.entity.ExternalAccountReference",
                "com.ses.entity.LicensePlan",
                "com.ses.entity.LicenseAssignment",
                "com.ses.dto.asset.OffboardingClearanceResultDto"
        );

        List<String> violations = new ArrayList<>();
        for (String className : assetClasses) {
            Class<?> clazz = Class.forName(className);
            for (Field field : clazz.getDeclaredFields()) {
                String fieldName = field.getName().toLowerCase();
                for (String forbidden : FORBIDDEN_KEYWORDS) {
                    if (fieldName.contains(forbidden)) {
                        violations.add(className + "#" + field.getName());
                    }
                }
            }
        }

        assertThat(violations)
                .withFailMessage("Found secret-containing fields in Asset entities/DTOs: %s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("2. Scan DDL migration files for forbidden secret column definitions")
    void scanDdlMigrationFiles() throws IOException {
        List<String> violations = new ArrayList<>();

        List<Path> migrationPaths = List.of(
                Paths.get("src/main/resources/db/migration/V129__asset_account_license_lifecycle.sql"),
                Paths.get("src/main/resources/db/migration/V130__asset_account_license_menu_permissions.sql"),
                Paths.get("src/main/resources/db/migration/V131__asset_offboarding_waiver_ledger.sql"),
                Paths.get("src/main/resources/db/migration/V132__asset_offboarding_waiver_scope_and_append_only_guards.sql")
        );
        for (Path migrationPath : migrationPaths) {
            if (!Files.exists(migrationPath)) {
                continue;
            }
            List<String> lines = Files.readAllLines(migrationPath, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim().toLowerCase();
                if (line.startsWith("create table") || line.startsWith("--") || line.isEmpty()) {
                    continue;
                }
                for (String forbidden : FORBIDDEN_KEYWORDS) {
                    if (line.contains(forbidden) && !line.contains("pii/secret非含有") && !line.contains("秘密非保存") && !line.contains("秘密値非含有") && !line.contains("秘密非含有")) {
                        violations.add(migrationPath.getFileName() + ": Line " + (i + 1) + ": " + lines.get(i));
                    }
                }
            }
        }

        assertThat(violations)
                .withFailMessage("Found forbidden column definitions in asset lifecycle DDL: %s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("3. Scan HTML and JS input fields for password/secret types or names")
    void scanHtmlAndJsFiles() throws IOException {
        List<Path> assetUiFiles = List.of(
                Paths.get("src/main/resources/templates/asset/list.html"),
                Paths.get("src/main/resources/templates/asset/inventory.html"),
                Paths.get("src/main/resources/templates/asset/accounts.html"),
                Paths.get("src/main/resources/templates/my/assets.html"),
                Paths.get("src/main/resources/static/js/modules/asset.js"),
                Paths.get("src/main/resources/static/js/modules/asset-inventory.js"),
                Paths.get("src/main/resources/static/js/modules/asset-accounts.js"),
                Paths.get("src/main/resources/static/js/modules/my-assets.js")
        );

        List<String> violations = new ArrayList<>();

        for (Path path : assetUiFiles) {
            if (!Files.exists(path)) continue;
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).toLowerCase();
                if (line.contains("type=\"password\"") || line.contains("type='password'") || line.contains("name=\"password\"")) {
                    violations.add(path.getFileName() + ": Line " + (i + 1) + ": " + lines.get(i));
                }
            }
        }

        assertThat(violations)
                .withFailMessage("Found secret/password input fields in Asset UI: %s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("4. 全src/main/java配下の全Javaファイルに対して、unmaskedシークレットロギング・例外メッセージ・監査payload漏洩を検査する（P1-03対応）")
    void scanAllJavaSourcesForSecretLeakage() throws IOException {
        // 全 src/main/java 配下の .java ファイルを走査
        Path rootSrc = Paths.get("src/main/java");
        List<String> violations = new ArrayList<>();

        if (!Files.exists(rootSrc)) {
            throw new IOException("src/main/java が存在しないためsecret scanを実行できません");
        }

        try (Stream<Path> stream = Files.walk(rootSrc)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            scanJavaSource(p, violations);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }

        assertThat(violations)
                .withFailMessage("全src/main/java配下のJavaファイルでシークレット漏洩が検出されました: %s", violations)
                .isEmpty();
    }

    /**
     * キーワードを含む日本語の状態メッセージを漏洩と誤判定しないため、文字列リテラルを除去した
     * Java呼出し単位で実値の式だけを検査する。括弧を数えるためmultilineのログ・例外・payloadも対象になる。
     */
    private static void scanJavaSource(Path path, List<String> violations) throws IOException {
        String source = Files.readString(path, StandardCharsets.UTF_8);
        scanInvocations(path, source, "\\blog\\.(info|warn|error|debug|trace)\\b", "LOG_SECRET", violations);
        scanInvocations(path, source, "\\bthrow\\s+new\\s+[A-Za-z0-9_$.]*Exception\\b", "EXCEPTION_SECRET", violations);
        scanInvocations(path, source,
                "(?:putPayload|addPayload|setAuditPayload|auditPayload|auditLog|recordAudit)\\s*\\(",
                "AUDIT_SECRET", violations);
    }

    private static void scanInvocations(Path path, String source, String startRegex, String kind,
                                        List<String> violations) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(startRegex).matcher(source);
        while (matcher.find()) {
            int open = source.indexOf('(', matcher.end() - 1);
            if (open < 0) {
                continue;
            }
            int close = matchingParen(source, open);
            if (close < 0) {
                continue;
            }
            String arguments = withoutCommentsAndStrings(source.substring(open + 1, close));
            if (containsUnmaskedSensitiveValue(arguments)) {
                int line = 1;
                for (int i = 0; i < matcher.start(); i++) {
                    if (source.charAt(i) == '\n') line++;
                }
                violations.add(path + ": Line " + line + " (" + kind + "): "
                        + source.substring(matcher.start(), Math.min(close + 1, source.length())).replace('\n', ' ').trim());
            }
        }
    }

    private static int matchingParen(String source, int open) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static String withoutCommentsAndStrings(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ")
                .replaceAll("(?s)\\\"(?:\\\\.|[^\\\"\\\\])*\\\"", " ")
                .replaceAll("(?s)'(?:\\\\.|[^'\\\\])*'", " ");
    }

    private static boolean containsUnmaskedSensitiveValue(String source) {
        String candidate = source
                .replaceAll("(?i)\\b(?:mask|redact|hash|digest|sanitize|scrub)[A-Za-z0-9_]*\\s*\\([^()]*\\)", " ");
        java.util.regex.Pattern sensitive = java.util.regex.Pattern.compile(
                "(?i)(?<![A-Za-z0-9_])(password|passwd|accountIdentifier|accessToken|refreshToken|bearerToken|"
                        + "clientSecret|apiKey|privateKey|recoveryCode|secretValue|credential|token|secret)(?![A-Za-z0-9_])");
        java.util.regex.Pattern getter = java.util.regex.Pattern.compile(
                "(?i)\\bget(?:Password|Passwd|Token|Secret|ApiKey|PrivateKey|RecoveryCode|Credential|AccountIdentifier)\\s*\\(");
        return sensitive.matcher(candidate).find() || getter.matcher(candidate).find();
    }
}
