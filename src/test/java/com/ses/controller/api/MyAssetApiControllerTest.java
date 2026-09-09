package com.ses.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.BaseIntegrationTest;
import com.ses.entity.Asset;
import com.ses.entity.AssetAssignment;
import com.ses.entity.Engineer;
import com.ses.entity.SysUser;
import com.ses.config.LoginUser;
import com.ses.mapper.AssetAssignmentMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.AssetService;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.LocalDate;
import java.util.Map;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("My Asset API Integration Tests (要員マイポータル)")
class MyAssetApiControllerTest extends BaseIntegrationTest {

    @AfterEach
    void clearTenantContext() {
        AccountingTenantContextHolder.clear();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AssetService assetService;

    @Autowired
    private AssetAssignmentMapper assetAssignmentMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EngineerAccountLinkService engineerAccountLinkService;

    @Test
    @DisplayName("GET /api/my/assets and POST /api/my/assets/report-lost")
    void testMyAssetPortalFlow() throws Exception {
        AccountingTenantContextHolder.setTenantId("default");
        String username = "eng_portal_user_" + System.nanoTime();
        // 1. ユーザー & 要員作成 & 紐付け
        SysUser user = SysUser.builder()
                .username(username)
                .password("pass123")
                .realName("山田 太郎")
                .role("要員")
                .tenantId("default")
                .status(1)
                .build();
        sysUserMapper.insert(user);

        Engineer engineer = Engineer.builder()
                .fullName("山田 太郎")
                .tenantId("default")
                .employmentType("正社員")
                .status("稼動中")
                .build();
        engineerMapper.insert(engineer);

        // 共有H2の既存fixtureが同じ採番値を再利用しても、リンクのDB一意制約に依存して失敗しないようにする。
        jdbcTemplate.update("DELETE FROM t_engineer_account_link WHERE engineer_id = ? OR sys_user_id = ?",
                engineer.getId(), user.getId());

        engineerAccountLinkService.link(engineer.getId(), user.getId(), 1L);

        // 2. 資産作成 & 貸与
        Asset asset = Asset.builder()
                .assetTag("AST-MY-001")
                .assetName("Surface Laptop 5")
                .category("PC")
                .status("IN_STOCK")
                .build();
        assetService.createAsset(asset, 1L);

        AssetAssignment assignment = AssetAssignment.builder()
                .assetId(asset.getId())
                .assigneeType("ENGINEER")
                .assigneeId(engineer.getId())
                .startDate(LocalDate.now())
                .status("ACTIVE")
                .build();
        assetAssignmentMapper.insert(assignment);

        // 3. マイ資産一覧取得
                mockMvc.perform(get("/api/my/assets")
                        .with(asEngineer(user))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.assets[*].assetTag").value(hasItem("AST-MY-001")));

        // 4. 紛失報告
        String lostPayload = objectMapper.writeValueAsString(Map.of(
                "assetId", asset.getId(),
                "incidentDetails", "電車内での置き忘れ"
        ));

        mockMvc.perform(post("/api/my/assets/report-lost")
                        .with(asEngineer(user))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lostPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("LOST"));
    }

    private RequestPostProcessor asEngineer(SysUser user) {
        LoginUser principal = new LoginUser(user,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_要員")));
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
