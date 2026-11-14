package com.ses.config.forwarded;

import com.ses.common.util.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 実Tomcat試験用のecho endpoint（{@link ForwardedHeaderServletContainerIntegrationTest} と
 * {@link ForwardedHeaderUntrustedPeerServletContainerIntegrationTest} が {@code @Import} する）。
 *
 * <p>認証不要の {@code /lib/**} 配下に置き、servlet側から見えるURI・remote address・scheme・headerを返す。
 * {@code @TestConfiguration} のため他のテストのcomponent scanには入らない。controllerはこのclassのmember class
 * としてimport時に登録される（{@code @Bean} を併記すると同じmappingが2つになるため書かない）。
 */
@TestConfiguration
public class ForwardedHeaderEchoTestConfiguration {

    static final String ECHO = "/lib/__forwarded-header-echo";

    @RestController
    public static class ForwardedHeaderEcho {
        @GetMapping(ECHO)
        public Map<String, Object> echo(HttpServletRequest request) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("requestUri", request.getRequestURI());
            body.put("contextPath", request.getContextPath());
            body.put("remoteAddr", request.getRemoteAddr());
            body.put("secure", request.isSecure());
            body.put("serverName", request.getServerName());
            body.put("requestUrl", request.getRequestURL().toString());
            body.put("clientIp", String.valueOf(request.getAttribute(ClientIpResolver.CLIENT_IP_ATTRIBUTE)));
            body.put("prefixHeader", String.valueOf(request.getHeader("X-Forwarded-Prefix")));
            body.put("forwardedHeader", String.valueOf(request.getHeader("Forwarded")));
            body.put("forwardedForHeader", String.valueOf(request.getHeader("X-Forwarded-For")));
            return body;
        }
    }
}
