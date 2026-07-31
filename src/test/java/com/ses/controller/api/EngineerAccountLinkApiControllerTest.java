package com.ses.controller.api;

import com.ses.entity.SysUser;
import com.ses.service.EngineerAccountLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 紐付け候補APIのテスト。
 *
 * <p>候補0件のとき、以前は空配列を返すだけで理由が分からず、画面は空のドロップダウンを
 * 出して黙って失敗していた。3条件のどれで落ちたかが {@code emptyReason} に出ることを固定する。
 */
@WebMvcTest(EngineerAccountLinkApiController.class)
class EngineerAccountLinkApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EngineerAccountLinkService linkService;
    @MockBean
    private com.ses.mapper.SysUserMapper sysUserMapper;
    @MockBean
    private com.ses.service.security.PersistentSessionService persistentSessionService;
    @MockBean
    private com.ses.service.security.AuthorizationService authorizationService;

    @BeforeEach
    void allowMockMvcSessions() {
        when(persistentSessionService.validateAndTouch(any(), any())).thenReturn(true);
        when(authorizationService.isAllowed(any(), anyString())).thenReturn(true);
        when(linkService.findLinkedUserIds(any())).thenReturn(java.util.Set.of());
    }

    private static SysUser user(Long id, String name, int status) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setUsername(name);
        u.setRole("要員");
        u.setStatus(status);
        return u;
    }

    @Test
    @WithMockUser(roles = "管理者")
    void candidates_要員ロールのユーザーが1件も無ければ理由を返す() throws Exception {
        when(sysUserMapper.selectList(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/engineers/1/account-link/candidates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates").isEmpty())
                .andExpect(jsonPath("$.data.emptyReason").value("NO_ENGINEER_ROLE_USER"));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void candidates_要員ロールが全員無効なら理由を分けて返す() throws Exception {
        when(sysUserMapper.selectList(any())).thenReturn(List.of(user(10L, "eng1", 0)));

        mockMvc.perform(get("/api/engineers/1/account-link/candidates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates").isEmpty())
                .andExpect(jsonPath("$.data.emptyReason").value("ALL_INACTIVE"));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void candidates_有効な要員が全員紐付け済みなら理由を分けて返す() throws Exception {
        when(sysUserMapper.selectList(any())).thenReturn(List.of(user(10L, "eng1", 1)));
        when(linkService.findLinkedUserIds(any())).thenReturn(java.util.Set.of(10L));

        mockMvc.perform(get("/api/engineers/1/account-link/candidates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates").isEmpty())
                .andExpect(jsonPath("$.data.emptyReason").value("ALL_LINKED"));
    }

    @Test
    @WithMockUser(roles = "管理者")
    void candidates_未紐付けの有効な要員は候補になりemptyReasonはnull() throws Exception {
        when(sysUserMapper.selectList(any())).thenReturn(List.of(user(10L, "eng1", 1), user(11L, "eng2", 0)));

        mockMvc.perform(get("/api/engineers/1/account-link/candidates"))
                .andExpect(status().isOk())
                // 無効ユーザー(eng2)は候補に含めない。
                .andExpect(jsonPath("$.data.candidates.length()").value(1))
                .andExpect(jsonPath("$.data.candidates[0].username").value("eng1"))
                .andExpect(jsonPath("$.data.emptyReason").doesNotExist());
    }
}
