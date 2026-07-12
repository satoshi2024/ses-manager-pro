package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.notification.NotificationDto;
import com.ses.entity.SysUser;
import com.ses.service.NotificationGenerateService;
import com.ses.service.NotificationService;
import com.ses.service.SysUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationApiController.class)
@AutoConfigureMockMvc(addFilters = false) // Disable security filters for simple unit test if needed
class NotificationApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NotificationService notificationService;

    @MockBean
    private com.ses.config.MenuPermissionFilter menuPermissionFilter;

    @MockBean
    private NotificationGenerateService notificationGenerateService;

    @MockBean
    private SysUserService sysUserService;

    @BeforeEach
    void setUp() {
        SysUser mockUser = new SysUser();
        mockUser.setId(1L);
        mockUser.setUsername("user");
        doReturn(mockUser).when(sysUserService).getOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        doReturn(mockUser).when(sysUserService).getOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class), anyBoolean());
    }

    @Test
    @WithMockUser(username = "user")
    void testGetNotifications() throws Exception {
        NotificationDto dto = NotificationDto.builder()
                .type("AI_MATCHING")
                .message("Test message")
                .build();
        when(notificationService.getRecentNotifications(1L)).thenReturn(Collections.singletonList(dto));

        mockMvc.perform(get("/api/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].type").value("AI_MATCHING"))
                .andExpect(jsonPath("$.data[0].message").value("Test message"));
    }

    @Test
    @WithMockUser(username = "user")
    void testGetNotificationsPage() throws Exception {
        Page<NotificationDto> page = new Page<>(1, 10);
        when(notificationService.pageForUser(1L, 1L, 10L, null, null)).thenReturn(page);

        mockMvc.perform(get("/api/notifications/page")
                        .param("current", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @WithMockUser(username = "user")
    void testGetUnreadCount() throws Exception {
        when(notificationService.unreadCount(1L)).thenReturn(5L);

        mockMvc.perform(get("/api/notifications/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(5));
    }

    @Test
    @WithMockUser(username = "user")
    void testMarkRead() throws Exception {
        mockMvc.perform(put("/api/notifications/100/read").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(notificationService).markRead(100L, 1L);
    }

    @Test
    @WithMockUser(username = "user")
    void testMarkAllRead() throws Exception {
        mockMvc.perform(put("/api/notifications/read-all").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(notificationService).markAllRead(1L);
    }

    @Test
    @WithMockUser(roles = "管理者")
    void testGenerateNotifications() throws Exception {
        mockMvc.perform(post("/api/notifications/generate").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(notificationGenerateService).generateAll();
    }
}
