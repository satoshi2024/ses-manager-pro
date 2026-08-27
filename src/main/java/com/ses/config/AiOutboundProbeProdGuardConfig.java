package com.ses.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 本番で {@code ai.outbound-probe.enabled=true} を許さない（環境変数でのフルプロンプト留存を拒否）。
 */
@Configuration
@Profile("prod")
public class AiOutboundProbeProdGuardConfig {

    @Bean
    InitializingBean aiOutboundProbeMustStayDisabledInProd(
            @Value("${ai.outbound-probe.enabled:false}") boolean enabled) {
        return () -> {
            if (enabled) {
                throw new IllegalStateException(
                        "prod では ai.outbound-probe.enabled=true を許可しません（fail-closed / REV-B2.1-P2-002）。");
            }
        };
    }
}
