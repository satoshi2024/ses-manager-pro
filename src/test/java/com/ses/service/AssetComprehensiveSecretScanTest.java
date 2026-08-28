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
        Path migrationPath = Paths.get("src/main/resources/db/migration/V129__asset_account_license_lifecycle.sql");
        if (!Files.exists(migrationPath)) {
            return;
        }

        List<String> lines = Files.readAllLines(migrationPath, StandardCharsets.UTF_8);
        List<String> violations = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim().toLowerCase();
            if (line.startsWith("create table") || line.startsWith("--") || line.isEmpty()) {
                continue;
            }
            for (String forbidden : FORBIDDEN_KEYWORDS) {
                if (line.contains(forbidden) && !line.contains("pii/secret非含有") && !line.contains("秘密非保存") && !line.contains("秘密値非含有") && !line.contains("秘密非含有")) {
                    violations.add("V129: Line " + (i + 1) + ": " + lines.get(i));
                }
            }
        }

        assertThat(violations)
                .withFailMessage("Found forbidden column definitions in V129 DDL: %s", violations)
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
    @DisplayName("4. Scan Service, Controller & Exception source files for unmasked secret logging & exception leakage")
    void scanServiceLogsAndExceptions() throws IOException {
        List<Path> scanDirs = List.of(
                Paths.get("src/main/java/com/ses/service/impl"),
                Paths.get("src/main/java/com/ses/controller/api")
        );
        List<String> violations = new ArrayList<>();

        for (Path dir : scanDirs) {
            if (!Files.exists(dir)) continue;
            try (Stream<Path> stream = Files.walk(dir)) {
                stream.filter(p -> p.toString().endsWith(".java") && (p.getFileName().toString().contains("Asset") || p.getFileName().toString().contains("ExternalAccount") || p.getFileName().toString().contains("License")))
                        .forEach(p -> {
                            try {
                                List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                                for (int i = 0; i < lines.size(); i++) {
                                    String line = lines.get(i);
                                    // ログ出力の検査
                                    if ((line.contains("log.info") || line.contains("log.warn") || line.contains("log.error") || line.contains("log.debug"))
                                            && line.contains("accountIdentifier") && !line.contains("maskIdentifier") && !line.contains("//")) {
                                        violations.add(p.getFileName() + ": Line " + (i + 1) + " (Log): " + line.trim());
                                    }
                                    // 例外メッセージの検査
                                    if (line.contains("throw new BusinessException") && (line.contains("password") || line.contains("token") || line.contains("secret"))) {
                                        violations.add(p.getFileName() + ": Line " + (i + 1) + " (Exception): " + line.trim());
                                    }
                                }
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
            }
        }

        assertThat(violations)
                .withFailMessage("Found unmasked logging or secret leakage in asset services/controllers: %s", violations)
                .isEmpty();
    }
}
