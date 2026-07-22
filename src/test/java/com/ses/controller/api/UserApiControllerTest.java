package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.SysUser;
import com.ses.service.SysUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
    private com.ses.mapper.SysUserMapper sysUserMapper;
    @MockBean
    private PasswordEncoder passwordEncoder;
    @MockBean
    private com.ses.mapper.EngineerSalesMapper engineerSalesMapper;
    @MockBean
    private com.ses.service.EngineerAccountLinkService engineerAccountLinkService;

    @Test
    @WithMockUser
    void page_一覧は200() throws Exception {
        when(sysUserService.page(any(), any())).thenReturn(new Page<>());
        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @WithMockUser
    void save_正常は200() throws Exception {
        when(sysUserMapper.countUsernameIncludingDeleted(any(), any())).thenReturn(0L);
        when(passwordEncoder.encode(anyString())).thenReturn("ENC");
        when(sysUserService.save(any())).thenReturn(true);

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
    }

    @Test
    @WithMockUser
    void save_ユーザー名が短いと400() throws Exception {
        Map<String, Object> body = Map.of("username", "ab", "password", "pass1234", "role", "営業");
        mockMvc.perform(post("/api/users").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @WithMockUser
    void save_弱いパスワードは業務エラー() throws Exception {
        when(sysUserMapper.countUsernameIncludingDeleted(any(), any())).thenReturn(0L);
        // 数字なし・8文字未満のパスワード → パスワードポリシー違反(BusinessException=500)
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
        when(sysUserService.getOne(any())).thenReturn(null); // guardNotSelf 通過
        when(engineerSalesMapper.selectCount(any())).thenReturn(3L);

        mockMvc.perform(delete("/api/users/5").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("3")));
        verify(sysUserService, never()).removeById(any());
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void delete_担当なしは成功() throws Exception {
        when(sysUserService.getOne(any())).thenReturn(null);
        when(engineerSalesMapper.selectCount(any())).thenReturn(0L);
        // removeById(Serializable) を明示スタブする（any() は entity 版オーバーロードに解決され、
        // コントローラが呼ぶ removeById(Long) が未スタブ=false になり404となるのを防ぐ）。
        when(sysUserService.removeById(any(Long.class))).thenReturn(true);

        mockMvc.perform(delete("/api/users/5").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void updateStatus_無効化時に現任担当ありは拒否() throws Exception {
        when(sysUserService.getOne(any())).thenReturn(null);
        when(engineerSalesMapper.selectCount(any())).thenReturn(2L);

        mockMvc.perform(put("/api/users/5/status?status=0").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("2")));
        verify(sysUserService, never()).updateById(any());
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void update_営業から他ロールへの変更で現任担当ありは拒否() throws Exception {
        when(sysUserMapper.countUsernameIncludingDeleted(any(), any())).thenReturn(0L);
        when(sysUserService.getOne(any())).thenReturn(null); // 自己変更ガード通過
        SysUser old = SysUser.builder().username("sales9").role("営業").build();
        old.setId(5L);
        when(sysUserService.getById(5L)).thenReturn(old);
        when(engineerSalesMapper.selectCount(any())).thenReturn(1L);

        SysUser body = SysUser.builder().username("sales9").role("HR").build();
        body.setId(5L);
        mockMvc.perform(put("/api/users/5").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("1")));
        verify(sysUserService, never()).updateById(any());
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void update_statusを含めても無効化ガードを迂回できず_statusは無視される() throws Exception {
        // G1: 汎用 update に status=0 を混ぜても、status は null 化され updateById に渡らない。
        when(sysUserMapper.countUsernameIncludingDeleted(any(), any())).thenReturn(0L);
        when(sysUserService.getOne(any())).thenReturn(null);
        SysUser old = SysUser.builder().username("sales9").role("営業").build();
        old.setId(5L);
        when(sysUserService.getById(5L)).thenReturn(old);
        when(sysUserService.updateById(any())).thenReturn(true);

        SysUser body = SysUser.builder().username("sales9").role("営業").build();
        body.setId(5L);
        body.setStatus(0); // 無効化を混ぜる
        mockMvc.perform(put("/api/users/5").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        org.mockito.ArgumentCaptor<SysUser> captor = org.mockito.ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserService).updateById(captor.capture());
        org.junit.jupiter.api.Assertions.assertNull(captor.getValue().getStatus(),
                "status は無視され updateById に渡らないこと");
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void update_ロール不変なら担当ありでも他項目編集は成功() throws Exception {
        when(sysUserMapper.countUsernameIncludingDeleted(any(), any())).thenReturn(0L);
        when(sysUserService.getOne(any())).thenReturn(null);
        SysUser old = SysUser.builder().username("sales9").role("営業").build();
        old.setId(5L);
        when(sysUserService.getById(5L)).thenReturn(old);
        when(sysUserService.updateById(any())).thenReturn(true);

        // ロールは営業のまま（変更なし）→ 担当ガードは発動しない
        SysUser body = SysUser.builder().username("sales9").role("営業").build();
        body.setId(5L);
        mockMvc.perform(put("/api/users/5").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(engineerSalesMapper, never()).selectCount(any());
    }
}
