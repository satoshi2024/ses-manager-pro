package com.ses.config.integrationhub;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** connectorが保持するraw request-targetをservlet filterへ一方向に渡す設定。 */
@Configuration(proxyBeanMethods = false)
public class ExternalApiTomcatConfiguration {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> externalApiRawRequestTargetCustomizer(
            IntegrationHubExternalApiProperties properties) {
        return factory -> {
            factory.addEngineValves(new ExternalApiRawRequestTargetValve());
            factory.addConnectorCustomizers(connector -> {
                if (Boolean.TRUE.equals(properties.getPublicApi().getEnabled())
                        && connector.getProtocol().toUpperCase(java.util.Locale.ROOT).contains("AJP")) {
                    throw new IllegalStateException("NF05の有効時はAJP connectorを使用できません");
                }
            });
            factory.addContextCustomizers(context -> context.addLifecycleListener(event -> {
                if (org.apache.catalina.Lifecycle.BEFORE_START_EVENT.equals(event.getType())
                        && Boolean.TRUE.equals(properties.getPublicApi().getEnabled())) {
                    for (org.apache.catalina.Container container = context; container != null; container = container.getParent()) {
                        if (container instanceof org.apache.catalina.Engine engine && engine.getService() != null) {
                            for (var connector : engine.getService().findConnectors()) {
                                if (connector.getProtocol().toUpperCase(java.util.Locale.ROOT).contains("AJP"))
                                    throw new IllegalStateException("NF05の有効時は追加AJP connectorを使用できません");
                            }
                        }
                        for (org.apache.catalina.Valve valve : container.getPipeline().getValves()) {
                            if (valve instanceof org.apache.catalina.valves.RemoteIpValve) {
                                throw new IllegalStateException("NF05の有効時はRemoteIpValveを使用できません");
                            }
                        }
                    }
                }
            }));
        };
    }
}
