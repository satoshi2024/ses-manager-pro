package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.service.DigitalInvoiceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InboundDigitalInvoiceApiControllerTest {

    private static final String SECRET = "db password=secret";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DigitalInvoiceService digitalInvoiceService;

    @Test
    @WithMockUser(roles = "管理者")
    void 受入のシステム例外原文を返さない() throws Exception {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(InboundDigitalInvoiceApiController.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            when(digitalInvoiceService.acceptInboundReview(anyLong()))
                    .thenThrow(new RuntimeException(SECRET));

            mockMvc.perform(post("/api/inbound-invoices/1/review")
                            .param("action", "ACCEPT")
                            .with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message", is("error.invoice.acceptFailed")))
                    .andExpect(jsonPath("$.message", not(containsString("secret"))));

            for (ch.qos.logback.classic.spi.ILoggingEvent event : appender.list) {
                org.assertj.core.api.Assertions.assertThat(event.getFormattedMessage()).doesNotContain("secret");
                if (event.getThrowableProxy() != null && event.getThrowableProxy().getMessage() != null) {
                    org.assertj.core.api.Assertions.assertThat(event.getThrowableProxy().getMessage()).doesNotContain("secret");
                }
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @WithMockUser(roles = "管理者")
    void 受入の業務例外を安全に返す() throws Exception {
        when(digitalInvoiceService.acceptInboundReview(anyLong()))
                .thenThrow(new BusinessException("レビュー待ちのインボイスではありません。"));

        mockMvc.perform(post("/api/inbound-invoices/1/review")
                        .param("action", "ACCEPT")
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("レビュー待ち")))
                .andExpect(jsonPath("$.message", not(containsString("secret"))));
    }
}
