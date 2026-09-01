package com.ses.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Flyway マイグレーションバージョン重複検証テスト")
class FlywayMigrationVersionResolutionTest {

    @Test
    @DisplayName("db/migration と db/migration-dev の両ロケーションを同時に解決した際にバージョン重複がないこと")
    void verifyNoDuplicateMigrationVersionsAcrossMigrationAndMigrationDev() {
        Flyway flyway = Flyway.configure()
                .dataSource("jdbc:h2:mem:flyway_resolve_test;DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "")
                .locations("classpath:db/migration", "classpath:db/migration-dev")
                .load();

        MigrationInfo[] allMigrations = flyway.info().all();
        assertNotNull(allMigrations, "マイグレーション情報が取得できること");
        assertTrue(allMigrations.length > 0, "マイグレーションが存在すること");

        Set<String> seenVersions = new HashSet<>();
        for (MigrationInfo info : allMigrations) {
            if (info.getVersion() != null) {
                String versionStr = info.getVersion().getVersion();
                assertFalse(seenVersions.contains(versionStr),
                        "重複するFlywayマイグレーションバージョンが検出されました: V" + versionStr + " (" + info.getScript() + ")");
                seenVersions.add(versionStr);
            }
        }

        // V145 (migration-dev: seed_r3_current_modules),
        // V146 (migration-dev: seed_r3_approval_route_extension),
        // V144 (migration: digital_invoice_safe_diagnostics) がそれぞれ一意に解決されること
        assertTrue(seenVersions.contains("145"), "V145 (migration-dev: seed_r3_current_modules) が解決されること");
        assertTrue(seenVersions.contains("146"), "V146 (migration-dev: seed_r3_approval_route_extension) が解決されること");
        assertTrue(seenVersions.contains("144"), "V144 (digital_invoice_safe_diagnostics) が解決されること");

        // V144 のスクリプト名が digital_invoice_safe_diagnostics であること
        List<MigrationInfo> v144Info = Arrays.stream(allMigrations)
                .filter(m -> m.getVersion() != null && "144".equals(m.getVersion().getVersion()))
                .toList();
        assertEquals(1, v144Info.size(), "V144 は1件のみ存在すること");
        assertTrue(v144Info.get(0).getScript().contains("digital_invoice_safe_diagnostics"),
                "V144 のスクリプト名は digital_invoice_safe_diagnostics であること: " + v144Info.get(0).getScript());
    }
}
