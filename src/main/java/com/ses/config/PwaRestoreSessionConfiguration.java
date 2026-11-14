package com.ses.config;

import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** PWA復元proofのsession失効通知だけをservlet containerへ登録する。 */
@Configuration
public class PwaRestoreSessionConfiguration {
    @Bean
    public ServletListenerRegistrationBean<PwaRestoreSessionListener> pwaRestoreSessionListenerRegistration(
            com.ses.service.pwa.PwaRestoreSessionRegistry registry) {
        return new ServletListenerRegistrationBean<>(new PwaRestoreSessionListener(registry));
    }
}
