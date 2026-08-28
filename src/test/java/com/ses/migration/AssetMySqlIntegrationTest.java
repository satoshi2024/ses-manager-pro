package com.ses.migration;

import com.ses.entity.Asset;
import com.ses.entity.AssetAssignment;
import com.ses.entity.ExternalAccountReference;
import com.ses.entity.ExternalAccountSystem;
import com.ses.entity.LicensePlan;
import com.ses.mapper.AssetAssignmentMapper;
import com.ses.mapper.AssetMapper;
import com.ses.mapper.ExternalAccountReferenceMapper;
import com.ses.mapper.ExternalAccountSystemMapper;
import com.ses.mapper.LicensePlanMapper;
import com.ses.service.AssetAssignmentService;
import com.ses.service.AssetService;
import com.ses.service.ExternalAccountService;
import com.ses.service.LicenseService;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 実MySQL 8コンテナ上での資産・アカウント・ライセンスDDL・排他制御・状態整合性テスト
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class AssetMySqlIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_asset_mysql")
            .withUsername("root")
            .withPassword("ses");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private AssetService assetService;

    @Autowired
    private AssetAssignmentService assetAssignmentService;

    @Autowired
    private AssetMapper assetMapper;

    @Autowired
    private AssetAssignmentMapper assetAssignmentMapper;

    @Autowired
    private ExternalAccountService externalAccountService;

    @Autowired
    private ExternalAccountSystemMapper externalAccountSystemMapper;

    @Autowired
    private ExternalAccountReferenceMapper externalAccountReferenceMapper;

    @Autowired
    private LicenseService licenseService;

    @Autowired
    private LicensePlanMapper licensePlanMapper;

    @Test
    @DisplayName("MySQL DDL: Asset creation and row lock verification")
    void testAssetCreationAndRowLockOnMySQL() {
        Asset asset = Asset.builder()
                .assetTag("MYSQL-AST-001")
                .assetName("MySQL ThinkPad Test")
                .category("PC")
                .status("IN_STOCK")
                .build();
        assetService.createAsset(asset, 1L);

        assertNotNull(asset.getId());
        Asset locked = assetMapper.selectByIdForUpdate(asset.getId());
        assertNotNull(locked);
        assertEquals("IN_STOCK", locked.getStatus());
    }

    @Test
    @DisplayName("MySQL DDL: Asset assignment and return lifecycle")
    void testAssetAssignmentLifecycleOnMySQL() {
        Asset asset = Asset.builder()
                .assetTag("MYSQL-AST-002")
                .assetName("MySQL Display Test")
                .category("MONITOR")
                .status("IN_STOCK")
                .build();
        assetService.createAsset(asset, 1L);

        AssetAssignment assignment = assetAssignmentService.createAssignment(
                asset.getId(), "ENGINEER", 7001L, LocalDate.now(), LocalDate.now().plusMonths(1), null, "MySQL Assign", 1L);
        assertNotNull(assignment.getId());
        assertEquals("ACTIVE", assignment.getStatus());

        Asset assignedAsset = assetService.getById(asset.getId());
        assertEquals("ASSIGNED", assignedAsset.getStatus());

        AssetAssignment returned = assetAssignmentService.returnAssignment(
                assignment.getId(), LocalDate.now(), null, "MySQL Returned", 1L);
        assertEquals("RETURNED", returned.getStatus());

        Asset returnedAsset = assetService.getById(asset.getId());
        assertEquals("IN_STOCK", returnedAsset.getStatus());
    }

    @Test
    @DisplayName("MySQL DDL: External account and license seat CAS")
    void testExternalAccountAndLicenseCasOnMySQL() {
        ExternalAccountSystem system = ExternalAccountSystem.builder()
                .systemCode("MYSQL_SYSTEM")
                .systemName("MySQL System")
                .systemType("IDP")
                .isActive(1)
                .build();
        externalAccountSystemMapper.insert(system);

        ExternalAccountReference ref = externalAccountService.registerAccountReference(
                system.getId(), "mysql.user@ses-test.jp", "ENGINEER", 7002L, "MEMBER", 1L);
        assertNotNull(ref.getId());
        assertEquals("ACTIVE", ref.getStatus());

        ExternalAccountReference revoked = externalAccountService.confirmRevoke(ref.getId(), 1L);
        assertEquals("REVOKED", revoked.getStatus());

        LicensePlan plan = LicensePlan.builder()
                .planCode("MYSQL-LIC-001")
                .planName("MySQL JetBrains Plan")
                .seatLimit(5)
                .allocatedCount(0)
                .status("ACTIVE")
                .build();
        licenseService.savePlan(plan, 1L);

        int updated = licensePlanMapper.incrementAllocatedCountWithCas(plan.getId(), plan.getVersion());
        assertEquals(1, updated);
        LicensePlan updatedPlan = licensePlanMapper.selectById(plan.getId());
        assertEquals(1, updatedPlan.getAllocatedCount());
    }
}
