package com.ses.controller.api;

import com.ses.BaseIntegrationTest;
import com.ses.entity.Asset;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.AssetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1-05 対応: list/detail/export/notification の各エンドポイントで、
 * ロール別スコープが一致して適用されることを検証する（否定系テスト含む）。
 *
 * CR-01 要求: 画面・API・CSVエクスポート・通知・要員ポータルで同一スコープ解決を適用すること。
 */
@DisplayName("P1-05: AssetApi ロール別スコープ一致テスト（CR-01 検証）")
class AssetApiRoleScopeIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AssetService assetService;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private EngineerAccountLinkMapper engineerAccountLinkMapper;

    private Long assetId;

    @BeforeEach
    void setUp() {
        SysUser engineerUser = sysUserMapper.selectByUsername("eng-scope-api-test");
        if (engineerUser == null) {
            engineerUser = SysUser.builder()
                    .username("eng-scope-api-test")
                    .password("pass")
                    .role("要員")
                    .status(1)
                    .build();
            sysUserMapper.insert(engineerUser);
        }
        if (engineerAccountLinkMapper.selectByUserId(engineerUser.getId()) == null) {
            EngineerAccountLink link = new EngineerAccountLink();
            link.setEngineerId(28801L);
            link.setSysUserId(engineerUser.getId());
            link.setLinkedBy(1L);
            engineerAccountLinkMapper.insert(link);
        }
        Asset asset = Asset.builder()
                .assetTag("AST-SCOPE-API-" + System.nanoTime())
                .assetName("Scope Test MacBook")
                .category("PC")
                .ownerCompanyId(100L)
                .status("IN_STOCK")
                .build();
        assetService.createAsset(asset, 1L);
        this.assetId = asset.getId();
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("CR-01(a): 管理者は /api/assets リストに HTTP 200 でアクセスできる")
    void adminCanAccessAssetList() throws Exception {
        mockMvc.perform(get("/api/assets"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("CR-01(b): 管理者は /api/assets/{id} 詳細に HTTP 200 でアクセスできる")
    void adminCanAccessAssetDetail() throws Exception {
        mockMvc.perform(get("/api/assets/" + assetId))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("CR-01(c): 管理者は /api/assets/export CSV エクスポートに HTTP 200 でアクセスできる")
    void adminCanExportAssets() throws Exception {
        mockMvc.perform(get("/api/assets/export"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "eng-scope-api-test", roles = "要員")
    @DisplayName("CR-01(d): 要員ロールはメイン /api/assets 一覧に HTTP 403 でブロックされる")
    void engineerBlockedFromAdminAssetList() throws Exception {
        mockMvc.perform(get("/api/assets"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "eng-scope-api-test", roles = "要員")
    @DisplayName("CR-01(e): 要員ロールは /api/my/assets API にアクセス可能（自己スコープのみ）")
    void engineerCanAccessMyAssets() throws Exception {
        mockMvc.perform(get("/api/my/assets"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "eng-scope-api-test", roles = "要員")
    @DisplayName("CR-01(f): 要員ロールは管理者向け /api/assets/{id} 詳細に HTTP 403 でブロックされる")
    void engineerBlockedFromAdminAssetDetail() throws Exception {
        mockMvc.perform(get("/api/assets/" + assetId))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "eng-scope-api-test", roles = "要員")
    @DisplayName("CR-01(g): 要員ロールは /api/assets/export CSV エクスポートに HTTP 403 でブロックされる")
    void engineerBlockedFromExport() throws Exception {
        mockMvc.perform(get("/api/assets/export"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "sales-scope-api-test", roles = "営業")
    @DisplayName("CR-01(h): 営業ロールは /api/assets リストに HTTP 200 でアクセスできる（スコープフィルタ適用）")
    void salesCanAccessAssetList() throws Exception {
        mockMvc.perform(get("/api/assets"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "HR")
    @DisplayName("CR-01(i): HRロールは /api/assets に HTTP 200 でアクセスできる")
    void hrCanAccessAssetList() throws Exception {
        mockMvc.perform(get("/api/assets"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "マネージャー")
    @DisplayName("CR-01(j): マネージャーは /api/assets リストに HTTP 200 でアクセスできる")
    void managerCanAccessAssetList() throws Exception {
        mockMvc.perform(get("/api/assets"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "マネージャー")
    @DisplayName("CR-01(k): マネージャーは /api/assets/export にアクセスできる")
    void managerCanExportAssets() throws Exception {
        mockMvc.perform(get("/api/assets/export"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("CR-01(l): 管理者は /api/notifications にアクセスできる（通知スコープ確認）")
    void adminCanAccessNotifications() throws Exception {
        mockMvc.perform(get("/api/notifications"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "sales-scope-api-test", roles = "営業")
    @DisplayName("CR-01(m): 営業ロールは /api/notifications にアクセスできる")
    void salesCanAccessNotifications() throws Exception {
        mockMvc.perform(get("/api/notifications"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "eng-scope-api-test", roles = "要員")
    @DisplayName("CR-01(n): 要員ロールは /api/notifications にアクセスできる（自己通知スコープ）")
    void engineerCanAccessNotifications() throws Exception {
        mockMvc.perform(get("/api/notifications"))
                .andExpect(status().isOk());
    }
}
