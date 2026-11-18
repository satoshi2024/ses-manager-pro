package com.ses.config.integrationhub;

import com.ses.mapper.SystemConfigMapper;
import com.ses.service.integrationhub.IntegrationHubOutboundStartupBarrier;
import com.ses.service.integrationhub.IntegrationHubWebhookDeliveryWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** runtime stateのDB障害が実SpringBoot起動と通常HTTP応答を止めないことを固定する。 */
@SpringBootTest(properties={
        "spring.datasource.url=jdbc:h2:mem:g3s_failure_boot;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL",
        "integration.hub.external-transport.enabled=true","integration.hub.provider.mode=MOCK",
        "integration.hub.topology.bound-tenant-id=default",
        "integration.hub.public-api.public-id-key=synthetic-failure-boot-public-id-key-at-least-32-bytes"})
@ActiveProfiles("test")
@Transactional
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class IntegrationHubOutboundFailureBootTest {
    @TestConfiguration static class Fault {
        @Bean @Primary SystemConfigMapper runtimeStateFailureMapper() {
            var mapper=mock(SystemConfigMapper.class);
            when(mapper.selectByIdForUpdate(IntegrationHubOutboundStartupBarrier.CONFIG_KEY)).thenThrow(new IllegalStateException("synthetic state DB failure"));
            return mapper;
        }
    }
    @Autowired IntegrationHubOutboundStartupBarrier barrier;
    @Autowired IntegrationHubWebhookDeliveryWorker worker;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Test void 状態DBが失敗してもアプリは起動しloginが応答する() throws Exception {
        assertThat(barrier.isRunning()).isTrue(); assertThat(barrier.isReady()).isFalse(); assertThat(worker.dispatchDue()).isZero();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/login"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }
}
