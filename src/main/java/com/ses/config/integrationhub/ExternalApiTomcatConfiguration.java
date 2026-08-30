package com.ses.config.integrationhub;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** connectorが保持するraw request-targetをservlet filterへ一方向に渡す設定。 */
@Configuration(proxyBeanMethods = false)
public class ExternalApiTomcatConfiguration {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> externalApiRawRequestTargetCustomizer() {
        return factory -> factory.addEngineValves(new ExternalApiRawRequestTargetValve());
    }
}
