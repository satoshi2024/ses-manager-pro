package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.entity.DigitalInvoice;
import com.ses.service.DigitalInvoiceService;
import com.ses.test.TenantTestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @BeforeEach
    void tenant付き認証主体を設定する() {
        TenantTestSecurity.bind("default");
    }

    @AfterEach
    void tenant付き認証主体を破棄する() {
        TenantTestSecurity.clear();
    }

    @Test
    @WithMockUser(roles = "マネージャー")
    void inbound一覧はserviceが返したJSONページをそのままシリアライズする() throws Exception {
        DigitalInvoice inbound = new DigitalInvoice();
        inbound.setId(901L);
        inbound.setDirection("RECEIVE");
        inbound.setProfile("Standard");
        inbound.setSpecificationVersion("1.1.3");
        inbound.setMessageId("MSG-INBOUND-SCOPE-NULL");
        inbound.setProviderMessageId("PROVIDER-INBOUND-SCOPE-NULL");
        inbound.setStatus("PENDING_REVIEW");
        Page<DigitalInvoice> page = new Page<>(1, 10);
        page.setTotal(1);
        page.setRecords(List.of(inbound));
        when(digitalInvoiceService.searchInboundInvoices(1, 10)).thenReturn(page);

        mockMvc.perform(get("/api/inbound-invoices").param("current", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.total", is(1)))
                .andExpect(jsonPath("$.data.records[0].direction", is("RECEIVE")))
                .andExpect(jsonPath("$.data.records[0].messageId", is("MSG-INBOUND-SCOPE-NULL")))
                .andExpect(jsonPath("$.data.records[0].invoiceId", nullValue()));
    }

    @Test
    @WithMockUser(roles = "マネージャー")
    void inbound一覧のscope拒否は403のJSON応答になる() throws Exception {
        when(digitalInvoiceService.searchInboundInvoices(1, 10))
                .thenThrow(BusinessException.of(403, "error.accessDenied"));

        mockMvc.perform(get("/api/inbound-invoices").param("current", "1").param("size", "10"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is(403)));
    }

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
