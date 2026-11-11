package com.ses.config;

import com.ses.service.ai.impl.CopilotBoundedHttpTransport;
import com.ses.service.ai.impl.CopilotDnsSnapshotResolver;
import com.ses.service.ai.impl.CopilotTransportProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.time.Clock;

/** Copilot専用HTTP client。旧AI RestTemplateには接続しない。 */
@Configuration
@EnableConfigurationProperties(CopilotTransportProperties.class)
public class CopilotTransportConfiguration {

    @Bean
    CopilotDnsSnapshotResolver copilotDnsSnapshotResolver(CopilotTransportProperties properties, Clock clock) {
        return new CopilotDnsSnapshotResolver(properties.toSnapshot(), clock);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @Lazy
    CopilotBoundedHttpTransport copilotBoundedHttpTransport(CopilotDnsSnapshotResolver resolver) {
        return new CopilotBoundedHttpTransport(resolver);
    }
}
