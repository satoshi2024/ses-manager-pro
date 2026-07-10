package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.notification.NotificationDto;
import com.ses.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.Collections;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationApiController.class)
class NotificationApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NotificationService notificationService;

    @Test
    @WithMockUser
    void testGetNotifications() throws Exception {
        NotificationDto dto = NotificationDto.builder()
                .type("AI_MATCHING")
                .message("Test message")
                .build();
        when(notificationService.getRecentNotifications()).thenReturn(Collections.singletonList(dto));

        mockMvc.perform(get("/api/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].type").value("AI_MATCHING"))
                .andExpect(jsonPath("$.data[0].message").value("Test message"));
    }
}
