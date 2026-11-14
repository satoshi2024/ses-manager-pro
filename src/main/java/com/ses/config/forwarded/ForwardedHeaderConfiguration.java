package com.ses.config.forwarded;

import com.ses.common.util.ClientIpResolver;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * {@code server.forward-headers-strategy: framework} のForwardedHeaderFilterを、清浄化付きの
 * {@link ForwardedHeaderSanitizingFilter} へ置き換える（FWD-SANITIZE）。
 *
 * <p>Boot標準の登録（{@code ServletWebServerFactoryAutoConfiguration.ForwardedHeaderFilterConfiguration}）は
 * {@code @ConditionalOnMissingFilterBean(ForwardedHeaderFilter.class)} のため、ここで
 * {@code FilterRegistrationBean<ForwardedHeaderFilter>} を登録すると標準の無条件filterは登録されない。
 * order・dispatcher type・relative redirectはBoot標準と同じにし、清浄化以外の挙動を変えない。
 *
 * <p>strategyを {@code native}/{@code none} にした場合はこの設定も標準filterも登録されない。本番では
 * {@link ForwardedHeaderProductionValidator} がframework以外での起動を拒否する。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "server.forward-headers-strategy", havingValue = "framework")
public class ForwardedHeaderConfiguration {

    @Bean
    public FilterRegistrationBean<ForwardedHeaderFilter> sanitizingForwardedHeaderFilter(
            ClientIpResolver clientIpResolver, ObjectProvider<ServerProperties> serverProperties) {
        ForwardedHeaderSanitizingFilter filter = new ForwardedHeaderSanitizingFilter(clientIpResolver);
        ServerProperties properties = serverProperties.getIfAvailable();
        if (properties != null) {
            filter.setRelativeRedirects(properties.getTomcat().isUseRelativeRedirects());
        }
        FilterRegistrationBean<ForwardedHeaderFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
