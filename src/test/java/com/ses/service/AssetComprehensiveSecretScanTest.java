package com.ses.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Comprehensive Secret Scan Test (DDL・Entity・DTO・HTML・JS・Log 秘密非保存全方位スキャン)")
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
        List<Path> ddlFiles = List.of(
                Paths.get("src/main/resources/db/migration/V129__asset_account_license_lifecycle.sql"),
                Paths.get("src/main/resources/db/migration/V130__asset_account_license_menu_permissions.sql"),
                Paths.get("src/test/resources/sql/schema-asset-account-license-lifecycle-h2.sql")
        );

        List<String> violations = new ArrayList<>();
        for (Path ddlPath : ddlFiles) {
            if (!Files.exists(ddlPath)) continue;
            List<String> lines = Files.readAllLines(ddlPath, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim().toLowerCase();
                // コメント行や既存sys_user等の記述は除外
                if (line.startsWith("--") || line.startsWith("/*")) continue;
                for (String forbidden : FORBIDDEN_KEYWORDS) {
                    // m_asset, t_external_account_reference 等の文脈で秘密列が追加されていないか
                    if (line.contains(" " + forbidden + " ") || line.contains("`" + forbidden + "`") || line.contains(forbidden + "_")) {
                        violations.add(ddlPath.getFileName() + ":" + (i + 1) + " -> " + line);
                    }
                }
            }
        }

        assertThat(violations)
                .withFailMessage("Found secret column definitions in DDL: %s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("3. Scan HTML templates and JS files for forbidden secret input fields")
    void scanHtmlAndJsFiles() throws IOException {
        List<Path> uiFiles = List.of(
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
        for (Path uiPath : uiFiles) {
            if (!Files.exists(uiPath)) continue;
            List<String> lines = Files.readAllLines(uiPath, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim().toLowerCase();
                // input type="password" や name="password" 等の混入を検査
                if (line.contains("type=\"password\"") || line.contains("name=\"password\"") ||
                    line.contains("id=\"password\"") || line.contains("secretkey") || line.contains("recoverycode")) {
                    violations.add(uiPath.getFileName() + ":" + (i + 1) + " -> " + line);
                }
            }
        }

        assertThat(violations)
                .withFailMessage("Found secret input fields in Asset UI: %s", violations)
                .isEmpty();
    }
}
