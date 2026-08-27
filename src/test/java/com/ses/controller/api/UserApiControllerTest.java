package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.SysUser;
import com.ses.service.SysUserService;
import com.ses.service.EngineerAccountLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ユーザーAPIのテスト（P8 Task9）: 一覧・登録・ユーザー名/パスワードのバリデーション。
 * 更新系の業務ロジックは SysUserService 側にあるため、Controllerは委譲とBean Validationを検証する。
 */
@WebMvcTest(UserApiController.class)
class UserApiControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SysUserService sysUserService;
    @MockBean
    private EngineerAccountLinkService engineerAccountLinkService;
    @MockBean
    private com.ses.service.security.MfaService mfaService;
    @MockBean
    private com.ses.service.security.AuthorizationService authorizationService;
    @MockBean
    private com.ses.service.security.PersistentSessionService persistentSessionService;

    @BeforeEach
    void allowMockMvcSessions() {
        when(persistentSessionService.validateAndTouch(any(), any())).thenReturn(true);
        when(authorizationService.isAllowed(any(), anyString())).thenReturn(true);
    }

    @Test
    @WithMockUser(roles = "管理者")
    void page_一覧は200() throws Exception {
        when(sysUserService.page(any(), any())).thenReturn(new Page<>());
        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 紐付けの無い要員アカウントはログインしてもマイ勤怠が常に403になり何も操作できない。
     * 一覧で気づけるよう engineerLinked を返すこと、および要員以外のロールには
     * 出さない（null のまま）ことを固定する。要員以外にも false が出ると全ユーザーへ
     * 不要な警告バッジが並ぶ。
     */
    @Test
    @WithMockUser(roles = "管理者")
    void page_要員ロールだけ紐付け有無を返す() throws Exception {
        SysUser engineerUser = SysUser.builder().username("eng1").role("要員").status(1).build();
        engineerUser.setId(10L);
        SysUser adminUser = SysUser.builder().username("admin").role("管理者").status(1).build();
        adminUser.setId(11L);

        Page<SysUser> page = new Page<>(1, 10, 2);
        page.setRecords(java.util.List.of(engineerUser, adminUser));
        when(sysUserService.page(any(), any())).thenReturn(page);
        when(engineerAccountLinkService.findLinkedUserIds(any())).thenReturn(java.util.Set.of());

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].engineerLinked").value(false))
                .andExpect(jsonPath("$.data.records[1].engineerLinked").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void page_紐付け済みの要員はtrueを返す() throws Exception {
        SysUser engineerUser = SysUser.builder().username("eng1").role("要員").status(1).build();
        engineerUser.setId(10L);

        Page<SysUser> page = new Page<>(1, 10, 1);
        page.setRecords(java.util.List.of(engineerUser));
        when(sysUserService.page(any(), any())).thenReturn(page);
        when(engineerAccountLinkService.findLinkedUserIds(any())).thenReturn(java.util.Set.of(10L));

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].engineerLinked").value(true))
                // パスワードは一覧へ出さない（既存の不変条件）。
                .andExpect(jsonPath("$.data.records[0].password").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void save_正常は200() throws Exception {
        SysUser u = SysUser.builder()
                .username("tester")
                .password("pass1234")
                .role("営業")
                .build();
        mockMvc.perform(post("/api/users").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(u)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(sysUserService).createUser(any(SysUser.class), any());
    }

    /**
     * 新規ユーザー登録はサービスへ委譲する（権限グループ割当はサービス内）。
     */
    @Test
    @WithMockUser(roles = "管理者")
    void save_新規ユーザーはcreateUserへ委譲する() throws Exception {
        SysUser u = SysUser.builder().username("tester").password("pass1234").role("営業").build();
        mockMvc.perform(post("/api/users").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(u)))
                .andExpect(status().isOk());

        verify(sysUserService).createUser(any(SysUser.class), any());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void save_ユーザー名が短いと400() throws Exception {
        Map<String, Object> body = Map.of("username", "ab", "password", "pass1234", "role", "営業");
        mockMvc.perform(post("/api/users").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        verify(sysUserService, never()).createUser(any(), any());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void save_弱いパスワードは業務エラー() throws Exception {
        // Bean Validationの@Size等ではなくサービス側ポリシー違反を想定
        doThrow(BusinessException.of(400, "error.user.passwordPolicy"))
                .when(sysUserService).createUser(any(), any());
        Map<String, Object> body = Map.of("username", "tester", "password", "abc", "role", "営業");
        mockMvc.perform(post("/api/users").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    // ===== S1-2: 現任担当を持つ営業ユーザーのライフサイクル操作ガード =====

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void delete_現任担当ありは拒否し件数を含める() throws Exception {
        doThrow(BusinessException.of("error.user.hasActiveSalesAssignments", 3L))
                .when(sysUserService).deleteUser(eq(5L), any());

        mockMvc.perform(delete("/api/users/5").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("3")));
        verify(sysUserService).deleteUser(eq(5L), any());
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void delete_担当なしは成功() throws Exception {
        mockMvc.perform(delete("/api/users/5").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(sysUserService).deleteUser(eq(5L), any());
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void updateStatus_無効化時に現任担当ありは拒否() throws Exception {
        doThrow(BusinessException.of("error.user.hasActiveSalesAssignments", 2L))
                .when(sysUserService).updateUserStatus(eq(5L), eq(0), any());

        mockMvc.perform(put("/api/users/5/status?status=0").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("2")));
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void update_営業から他ロールへの変更で現任担当ありは拒否() throws Exception {
        doThrow(BusinessException.of("error.user.hasActiveSalesAssignments", 1L))
                .when(sysUserService).updateUser(eq(5L), any(SysUser.class), any());

        SysUser body = SysUser.builder().username("sales9").role("HR").build();
        body.setId(5L);
        mockMvc.perform(put("/api/users/5").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("1")));
        verify(sysUserService).updateUser(eq(5L), any(SysUser.class), any());
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void update_statusを含めてもサービスへ委譲する() throws Exception {
        // status無視はサービス内の不変条件。Controllerは委譲のみ。
        SysUser body = SysUser.builder().username("sales9").role("営業").build();
        body.setId(5L);
        body.setStatus(0);
        mockMvc.perform(put("/api/users/5").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(sysUserService).updateUser(eq(5L), any(SysUser.class), any());
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void update_ロール不変なら担当ありでも他項目編集は成功() throws Exception {
        SysUser body = SysUser.builder().username("sales9").role("営業").build();
        body.setId(5L);
        mockMvc.perform(put("/api/users/5").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(sysUserService).updateUser(eq(5L), any(SysUser.class), any());
    }

    /**
     * ロール変更はサービスへ委譲する（scope世代更新・権限グループ再割当はサービス内）。
     */
    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void update_ロール変更はupdateUserへ委譲する() throws Exception {
        SysUser body = SysUser.builder().username("hruser1").role("マネージャー").build();
        body.setId(5L);
        mockMvc.perform(put("/api/users/5").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(sysUserService).updateUser(eq(5L), any(SysUser.class), any());
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void updateStatus_所属クローズ失敗時は成功レスポンスを返さない() throws Exception {
        doThrow(BusinessException.of("error.organization.closeFailed"))
                .when(sysUserService).updateUserStatus(eq(5L), eq(0), any());

        mockMvc.perform(put("/api/users/5/status?status=0").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        verify(sysUserService).updateUserStatus(eq(5L), eq(0), any());
    }
}
