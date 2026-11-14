package com.ses.config.integrationhub;

import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.web.embedded.tomcat.TomcatConnectorCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatContextCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalApiTomcatConfigurationTest {
    @Test
    void 有効時のAJPをconnector構築時に拒否する() {
        var properties = new IntegrationHubExternalApiProperties();
        properties.getPublicApi().setEnabled(true);
        var factory = mock(TomcatServletWebServerFactory.class);
        new ExternalApiTomcatConfiguration().externalApiRawRequestTargetCustomizer(properties).customize(factory);
        var captured = ArgumentCaptor.forClass(TomcatConnectorCustomizer[].class);
        verify(factory).addConnectorCustomizers(captured.capture());
        var customizer = captured.getValue()[0];
        assertThatThrownBy(() -> customizer.customize(new Connector("AJP/1.3"))).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> customizer.customize(new Connector())).doesNotThrowAnyException();
        properties.getPublicApi().setEnabled(false);
        assertThatCode(() -> customizer.customize(new Connector("AJP/1.3"))).doesNotThrowAnyException();
    }

    @Test
    void 有効時の親pipelineのRemoteIpValveを起動前に拒否する() {
        var properties = new IntegrationHubExternalApiProperties();
        properties.getPublicApi().setEnabled(true);
        var factory = mock(TomcatServletWebServerFactory.class);
        new ExternalApiTomcatConfiguration().externalApiRawRequestTargetCustomizer(properties).customize(factory);
        var captured = ArgumentCaptor.forClass(TomcatContextCustomizer[].class);
        verify(factory).addContextCustomizers(captured.capture());
        StandardContext context = new StandardContext();
        StandardHost host = new StandardHost();
        context.setParent(host);
        host.getPipeline().addValve(new RemoteIpValve());
        captured.getValue()[0].customize(context);
        var listener = context.findLifecycleListeners()[0];
        var event = new LifecycleEvent(context, Lifecycle.BEFORE_START_EVENT, null);
        assertThatThrownBy(() -> listener.lifecycleEvent(event)).isInstanceOf(IllegalStateException.class);
        properties.getPublicApi().setEnabled(false);
        assertThatCode(() -> listener.lifecycleEvent(event)).doesNotThrowAnyException();
    }
    @Test
    void 追加AJPconnectorもservice全体の起動前検査で拒否する() {
        var properties = new IntegrationHubExternalApiProperties(); properties.getPublicApi().setEnabled(true);
        var factory = mock(TomcatServletWebServerFactory.class);
        new ExternalApiTomcatConfiguration().externalApiRawRequestTargetCustomizer(properties).customize(factory);
        var captured = ArgumentCaptor.forClass(TomcatContextCustomizer[].class);
        verify(factory).addContextCustomizers(captured.capture());
        var context = new StandardContext(); var host = new StandardHost();
        var engine = new org.apache.catalina.core.StandardEngine();
        var service = mock(org.apache.catalina.Service.class); engine.setService(service);
        context.setParent(host); host.setParent(engine);
        when(service.findConnectors()).thenReturn(new Connector[]{new Connector("AJP/1.3")});
        captured.getValue()[0].customize(context);
        var listener = context.findLifecycleListeners()[0];
        var event = new LifecycleEvent(context, Lifecycle.BEFORE_START_EVENT, null);
        assertThatThrownBy(() -> listener.lifecycleEvent(event)).isInstanceOf(IllegalStateException.class);
        when(service.findConnectors()).thenReturn(new Connector[]{new Connector()});
        assertThatCode(() -> listener.lifecycleEvent(event)).doesNotThrowAnyException();
    }
}
