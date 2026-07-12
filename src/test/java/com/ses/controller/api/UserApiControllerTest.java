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
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    private PasswordEncoder passwordEncoder;

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
        when(sysUserService.count(any())).thenReturn(0L);
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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @WithMockUser
    void save_弱いパスワードは業務エラー() throws Exception {
        when(sysUserService.count(any())).thenReturn(0L);
        // 数字なし・8文字未満のパスワード → パスワードポリシー違反(BusinessException=500)
        Map<String, Object> body = Map.of("username", "tester", "password", "abc", "role", "営業");
        mockMvc.perform(post("/api/users").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }
}
