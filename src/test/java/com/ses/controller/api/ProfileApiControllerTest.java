package com.ses.controller.api;

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
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ProfileApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysUserService sysUserService;

    @Autowired
    private com.ses.service.AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        // reset passwords
        SysUser user = sysUserService.getById(1L);
        if(user != null) {
            SysUser update = new SysUser();
            update.setId(user.getId());
            update.setPassword("admin123");
            sysUserService.updateById(update);
        }
        SysUser sales = sysUserService.getById(2L);
        if(sales != null) {
            SysUser update = new SysUser();
            update.setId(sales.getId());
            update.setPassword("sales123");
            sysUserService.updateById(update);
        }
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void testChangePasswordSuccess() throws Exception {
        PasswordChangeRequest req = new PasswordChangeRequest();
        req.setCurrentPassword("admin123");
        req.setNewPassword("newAdmin456");

        mockMvc.perform(put("/api/profile/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        SysUser updatedUser = sysUserService.getById(1L);
        assertEquals("newAdmin456", updatedUser.getPassword());

        // 監査ログが記録されていること（平文パスワードが含まれないことの証明：AuditLogにボディのフィールド自体が存在しない）
        var logs = auditLogService.page(1, 10, "admin", "PUT").getRecords();
        boolean found = logs.stream().anyMatch(log -> log.getUri().equals("/api/profile/password"));
        assertTrue(found, "パスワード変更の監査ログが記録されていること");
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void testChangePasswordWrongCurrentPassword() throws Exception {
        PasswordChangeRequest req = new PasswordChangeRequest();
        req.setCurrentPassword("wrongpw");
        req.setNewPassword("newAdmin456");

        mockMvc.perform(put("/api/profile/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("現在のパスワードが正しくありません"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void testChangePasswordPolicyViolation() throws Exception {
        PasswordChangeRequest req = new PasswordChangeRequest();
        req.setCurrentPassword("admin123");
        req.setNewPassword("short");

        mockMvc.perform(put("/api/profile/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("パスワードは8文字以上で英字と数字を含めてください"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void testChangePasswordSameAsCurrent() throws Exception {
        PasswordChangeRequest req = new PasswordChangeRequest();
        req.setCurrentPassword("admin123");
        req.setNewPassword("admin123");

        mockMvc.perform(put("/api/profile/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("現在と同じパスワードは設定できません"));
    }

    @Test
    @WithAnonymousUser
    void testChangePasswordUnauthenticated() throws Exception {
        PasswordChangeRequest req = new PasswordChangeRequest();
        req.setCurrentPassword("admin123");
        req.setNewPassword("newAdmin456");

        mockMvc.perform(put("/api/profile/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(username = "sales1", roles = "SALES")
    void testChangePasswordSalesRole() throws Exception {
        PasswordChangeRequest req = new PasswordChangeRequest();
        req.setCurrentPassword("sales123");
        req.setNewPassword("newSales456");

        mockMvc.perform(put("/api/profile/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
                
        SysUser updatedUser = sysUserService.getById(2L);
        assertEquals("newSales456", updatedUser.getPassword());
    }
}
