package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.profile.PasswordChangeRequest;
import com.ses.entity.SysUser;
import com.ses.service.SysUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 自身のパスワード変更API（WS-E）の検証。
 *
 * H2(test プロファイル)を使用する結合テスト。DB書き込みはコミットされるため、
 * @BeforeEach で毎回既知のユーザー状態(admin / sales1)へリセットしてから検証する。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfileApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysUserService sysUserService;

    @Autowired
    private com.ses.service.AuditLogService auditLogService;

    private SysUser findByUsername(String username) {
        return sysUserService.getOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
    }

    @BeforeEach
    void setUp() {
        // admin(V2シードで作成済み)のパスワードを既知値へリセット
        SysUser admin = findByUsername("admin");
        if (admin != null) {
            SysUser update = new SysUser();
            update.setId(admin.getId());
            update.setPassword("admin123");
            sysUserService.updateById(update);
        }
        // sales1(非管理者)は本テスト用に用意する。なければ作成し、あればパスワードをリセット
        SysUser sales = findByUsername("sales1");
        if (sales == null) {
            SysUser created = SysUser.builder()
                    .username("sales1")
                    .password("sales123")
                    .realName("営業テスト")
                    .role("営業")
                    .status(1)
                    .build();
            sysUserService.save(created);
        } else {
            SysUser update = new SysUser();
            update.setId(sales.getId());
            update.setPassword("sales123");
            sysUserService.updateById(update);
        }
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void testChangePasswordSuccess() throws Exception {
        PasswordChangeRequest req = new PasswordChangeRequest();
        req.setCurrentPassword("admin123");
        req.setNewPassword("newAdmin456");

        mockMvc.perform(put("/api/profile/password")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        SysUser updatedUser = findByUsername("admin");
        assertEquals("newAdmin456", updatedUser.getPassword());

        // 監査ログが記録されていること（実行者名はテスト環境のモック認証では取得できないため
        // メソッド+URIで検証する）。監査ログはURI/メソッド/ステータスのみ記録し、
        // リクエストボディ（平文パスワード）は一切保存しない。
        var logs = auditLogService.page(1, 50, null, "PUT").getRecords();
        boolean found = logs.stream().anyMatch(log -> "/api/profile/password".equals(log.getUri()));
        assertTrue(found, "パスワード変更の監査ログが記録されていること");
        boolean leaked = logs.stream().anyMatch(log ->
                (log.getUri() != null && log.getUri().contains("newAdmin456")));
        assertFalse(leaked, "監査ログに平文パスワードが含まれないこと");
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void testChangePasswordWrongCurrentPassword() throws Exception {
        PasswordChangeRequest req = new PasswordChangeRequest();
        req.setCurrentPassword("wrongpw");
        req.setNewPassword("newAdmin456");

        mockMvc.perform(put("/api/profile/password")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("現在のパスワードが正しくありません"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void testChangePasswordPolicyViolation() throws Exception {
        PasswordChangeRequest req = new PasswordChangeRequest();
        req.setCurrentPassword("admin123");
        req.setNewPassword("short");

        mockMvc.perform(put("/api/profile/password")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("パスワードは8文字以上で英字と数字を含めてください"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void testChangePasswordSameAsCurrent() throws Exception {
        PasswordChangeRequest req = new PasswordChangeRequest();
        req.setCurrentPassword("admin123");
        req.setNewPassword("admin123");

        mockMvc.perform(put("/api/profile/password")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("新しいパスワードは現在のパスワードと同じにできません"));
    }

    @Test
    @WithAnonymousUser
    void testChangePasswordUnauthenticated() throws Exception {
        PasswordChangeRequest req = new PasswordChangeRequest();
        req.setCurrentPassword("admin123");
        req.setNewPassword("newAdmin456");

        mockMvc.perform(put("/api/profile/password")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(username = "sales1", roles = "営業")
    void testChangePasswordSalesRole() throws Exception {
        PasswordChangeRequest req = new PasswordChangeRequest();
        req.setCurrentPassword("sales123");
        req.setNewPassword("newSales456");

        mockMvc.perform(put("/api/profile/password")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        SysUser updatedUser = findByUsername("sales1");
        assertEquals("newSales456", updatedUser.getPassword());
    }
}
