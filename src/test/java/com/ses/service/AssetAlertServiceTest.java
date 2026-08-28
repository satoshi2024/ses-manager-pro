package com.ses.service;

import com.ses.BaseIntegrationTest;
import com.ses.entity.Asset;
import com.ses.entity.AssetAssignment;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.Notification;
import com.ses.entity.SysUser;
import com.ses.mapper.AssetAssignmentMapper;
import com.ses.mapper.AssetMapper;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.NotificationMapper;
import com.ses.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Asset Alert & Deadline Monitoring Tests")
class AssetAlertServiceTest extends BaseIntegrationTest {

    @Autowired
    private AssetAlertService assetAlertService;

    @Autowired
    private AssetService assetService;

    @Autowired
    private AssetAssignmentMapper assetAssignmentMapper;

    @Autowired
    private AssetMapper assetMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private EngineerAccountLinkMapper engineerAccountLinkMapper;

    @Autowired
    private NotificationMapper notificationMapper;

    @Test
    @DisplayName("Check overdue assignments: alert count > 0 for past expected return date")
    void testCheckOverdueAssignments() {
        SysUser engineerUser = SysUser.builder()
                .username("asset-alert-" + System.nanoTime())
                .password("test")
                .realName("資産通知テスト要員")
                .role("要員")
                .status(1)
                .build();
        sysUserMapper.insert(engineerUser);
        EngineerAccountLink accountLink = new EngineerAccountLink();
        accountLink.setEngineerId(101L);
        accountLink.setSysUserId(engineerUser.getId());
        engineerAccountLinkMapper.insert(accountLink);

        Asset asset = Asset.builder()
                .assetTag("AST-ALERT-001")
                .assetName("Overdue Test Device")
                .category("PC")
                .status("ASSIGNED")
                .build();
        assetService.createAsset(asset, 1L);

        // 過去の返却予定日で貸与レコード作成
        AssetAssignment assignment = AssetAssignment.builder()
                .assetId(asset.getId())
                .assigneeType("ENGINEER")
                .assigneeId(101L)
                .startDate(LocalDate.now().minusMonths(2))
                .expectedReturnDate(LocalDate.now().minusDays(5))
                .status("ACTIVE")
                .build();
        assetAssignmentMapper.insert(assignment);

        int alerts = assetAlertService.checkOverdueAssignments();
        assertThat(alerts).isGreaterThanOrEqualTo(1);

        List<AssetAssignment> overdueList = assetAlertService.getOverdueAssignments();
        assertThat(overdueList).isNotEmpty();
        assertThat(notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientUserId, engineerUser.getId())
                .eq(Notification::getType, "ASSET_OVERDUE")))
                .hasSize(1);
    }

    @Test
    @DisplayName("Check expiring leases: alert count > 0 for lease expiring within 30 days")
    void testCheckExpiringLeases() {
        Asset expiringAsset = Asset.builder()
                .assetTag("AST-LEASE-001")
                .assetName("Expiring Lease Laptop")
                .category("PC")
                .status("IN_STOCK")
                .leaseExpiry(LocalDate.now().plusDays(10))
                .build();
        assetService.createAsset(expiringAsset, 1L);

        int alerts = assetAlertService.checkExpiringLeases();
        assertThat(alerts).isGreaterThanOrEqualTo(1);
    }
}
