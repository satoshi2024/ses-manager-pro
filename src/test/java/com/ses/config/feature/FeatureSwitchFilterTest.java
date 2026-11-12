package com.ses.config.feature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.result.ApiResult;
import com.ses.controller.CustomErrorController;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.ui.ExtendedModelMap;

import java.util.List;
import java.util.Map;

import static com.ses.config.feature.FeatureSwitchTestSupport.allOff;
import static com.ses.config.feature.FeatureSwitchTestSupport.allOn;
import static com.ses.config.feature.FeatureSwitchTestSupport.disabledOnly;
import static com.ses.config.feature.FeatureSwitchTestSupport.enabledOnly;
import static com.ses.config.feature.FeatureSwitchTestSupport.provider;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 機能スイッチfilterの遮断・素通し・独立性をServlet単体で検証する（DB・Docker不要）。
 */
class FeatureSwitchFilterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 各機能の代表的なAPI入口（内部・本人・portal・管理者専用を含む）。 */
    private static final Map<Feature, List<String>> API_SAMPLES = Map.of(
            Feature.NF01, List.of("/api/lifecycle/cases", "/api/lifecycle/templates/3",
                    "/api/my/lifecycle/tasks/pending"),
            Feature.NF02, List.of("/api/service-desk/requests", "/api/service-desk/health/snapshots",
                    "/api/customer-success/health/9", "/api/customer-success/qbrs",
                    "/api/portal/customer/service-desk/requests/5/comments"),
            Feature.NF03, List.of("/api/certification-learning-gap/count",
                    "/api/certification-learning-gap/export",
                    "/api/my/certification-learning-gap/learning-plans"),
            // NF-04はAPIを遮断しない（日次入力queueと下書き復元は常時有効）
            Feature.NF04, List.of(),
            Feature.NF10, List.of("/api/management-reports/runs", "/api/management-reports/schedules/7/enable"));

    /** 各機能の代表的な画面・静的入口。 */
    private static final Map<Feature, List<String>> PAGE_SAMPLES = Map.of(
            Feature.NF01, List.of("/lifecycle", "/lifecycle/12", "/lifecycle/templates", "/my/lifecycle"),
            Feature.NF02, List.of("/service-desk/requests", "/service-desk/requests/4",
                    "/customer-success/health", "/service-desk/health"),
            Feature.NF03, List.of("/certification-learning-skill-gap", "/my/certification-learning-skill-gap"),
            Feature.NF04, List.of("/service-worker.js", "/manifest.webmanifest"),
            Feature.NF10, List.of("/management-reports"));

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @EnumSource(value = Feature.class, names = "NF04", mode = EnumSource.Mode.EXCLUDE)
    void OFFの機能のAPIはCustomErrorControllerと同じApiResult形式の404になる(Feature feature) throws Exception {
        assertThat(API_SAMPLES.get(feature)).as("代表APIが空振りしないこと").isNotEmpty();
        FeatureSwitchFilter filter = filter(disabledOnly(feature));

        for (String uri : API_SAMPLES.get(feature)) {
            for (String method : List.of("GET", "POST", "DELETE")) {
                MockHttpServletResponse response = new MockHttpServletResponse();
                MockFilterChain chain = new MockFilterChain();

                filter.doFilter(new MockHttpServletRequest(method, uri), response, chain);

                assertThat(chain.getRequest()).as("%s %s はcontrollerへ到達しない", method, uri).isNull();
                assertThat(response.getStatus()).as(uri).isEqualTo(404);
                assertThat(response.getContentType()).startsWith("application/json");
                assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
                JsonNode body = JSON.readTree(response.getContentAsString());
                assertThat(body.get("code").asInt()).isEqualTo(404);
                assertThat(body.get("message").asText()).isEqualTo(FeatureSwitchFilter.NOT_FOUND_MESSAGE);
                assertThat(body.get("data").isNull()).isTrue();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Feature.class)
    void OFFの機能の画面と静的入口は統一エラーページへ委ねる404になる(Feature feature) throws Exception {
        FeatureSwitchFilter filter = filter(disabledOnly(feature));

        for (String uri : PAGE_SAMPLES.get(feature)) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(new MockHttpServletRequest("GET", uri), response, chain);

            assertThat(chain.getRequest()).as(uri).isNull();
            assertThat(response.getStatus()).as(uri).isEqualTo(404);
            // sendErrorのためbodyは書かず、error dispatch（CustomErrorController）に描画を任せる。
            assertThat(response.isCommitted()).isTrue();
            assertThat(response.getContentAsString()).isEmpty();
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        }
    }

    @ParameterizedTest
    @EnumSource(Feature.class)
    void 機能スイッチは互いに独立しOFFにした機能だけを遮断する(Feature disabled) throws Exception {
        FeatureSwitchFilter filter = filter(disabledOnly(disabled));

        for (Feature other : Feature.values()) {
            if (other == disabled) {
                continue;
            }
            for (String uri : samples(other)) {
                assertPassesThrough(filter, "GET", uri);
            }
        }
        // 逆に、この機能だけONにすると他の機能は全て遮断され、この機能だけ通る。
        FeatureSwitchFilter onlyThis = filter(enabledOnly(disabled));
        for (String uri : samples(disabled)) {
            assertPassesThrough(onlyThis, "GET", uri);
        }
        for (Feature other : Feature.values()) {
            if (other != disabled) {
                for (String uri : samples(other)) {
                    assertBlocked(onlyThis, uri);
                }
            }
        }
    }

    @Test
    void 全機能ONでは全ての入口が素通しになる() throws Exception {
        FeatureSwitchFilter filter = filter(allOn());

        for (Feature feature : Feature.values()) {
            for (String uri : samples(feature)) {
                assertPassesThrough(filter, "POST", uri);
            }
        }
    }

    @Test
    void 管理者でも未認証でもOFFの機能は同じ404になり迂回できない() throws Exception {
        FeatureSwitchFilter filter = filter(allOff());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_管理者"))));

        MockHttpServletResponse asAdmin = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/lifecycle/cases"), asAdmin, new MockFilterChain());
        SecurityContextHolder.clearContext();
        MockHttpServletResponse anonymous = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/lifecycle/cases"), anonymous, new MockFilterChain());

        assertThat(asAdmin.getStatus()).isEqualTo(404);
        assertThat(anonymous.getStatus()).isEqualTo(404);
        assertThat(asAdmin.getContentAsString()).isEqualTo(anonymous.getContentAsString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // NF-04でも既存セルフサービスの保存経路・user scope bootstrapは常時有効
            "/api/my/pwa/timesheet/daily", "/api/my/pwa/attendance/daily", "/api/my/pwa/expenses/drafts",
            "/api/my/pwa/change-requests/drafts", "/api/my/session-context", "/js/pwa-queue.js",
            // NF-04でもロック済み下書きの復元経路（再認証必須）は常時有効
            "/offline.html", "/api/my/pwa/restore/reauthenticate", "/api/my/pwa/restore/confirm",
            // NF配下ではない既存機能
            "/api/engineers", "/my/timesheet", "/my/expenses", "/portal/customer", "/api/portal/customer/invoices",
            "/api/admin/nf02-nf03/ownership-repair", "/api/contracts/renewal-calendar", "/dashboard", "/",
            // パス区切り境界（前方一致だけで誤遮断しない）
            "/lifecycle-report", "/api/lifecycles", "/api/management-reports-archive", "/service-desk-guide"
    })
    void 機能外のpathと常時有効な保存経路は全機能OFFでも遮断しない(String uri) throws Exception {
        assertPassesThrough(filter(allOff()), "POST", uri);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/lif%65cycle/cases", "/lif%65cycle", "/api/lifecycle;jsessionid=abc/cases",
            "//api//lifecycle/cases", "/api/management-reports/", "/service-worker.js;x=1",
            // Windows開発機のfile system classpathでは大文字小文字・末尾/の違いでも静的ファイルが解決され得る
            "/Service-Worker.js", "/manifest.webmanifest/"
    })
    void 表記ゆれやセミコロンでは迂回できない(String uri) throws Exception {
        assertBlocked(filter(allOff()), uri);
    }

    @Test
    void context配下に配置しても機能入口を照合できる() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ses/api/service-desk/requests");
        request.setContextPath("/ses");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(allOff()).doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentType()).startsWith("application/json");
    }

    @Test
    void 遮断応答はSecurity_chainを通らなくてもSpring_Security既定と同じ安全headerを持つ() throws Exception {
        MockHttpServletResponse plain = new MockHttpServletResponse();
        filter(allOff()).doFilter(new MockHttpServletRequest("GET", "/api/lifecycle/cases"), plain,
                new MockFilterChain());
        MockHttpServletRequest secureRequest = new MockHttpServletRequest("GET", "/service-worker.js");
        secureRequest.setSecure(true);
        MockHttpServletResponse secure = new MockHttpServletResponse();
        filter(allOff()).doFilter(secureRequest, secure, new MockFilterChain());

        for (MockHttpServletResponse response : List.of(plain, secure)) {
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
            assertThat(response.getHeader("Expires")).isEqualTo("0");
            assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
            assertThat(response.getHeader("X-XSS-Protection")).isEqualTo("0");
        }
        assertThat(plain.getHeader("Strict-Transport-Security")).isNull();
        assertThat(secure.getHeader("Strict-Transport-Security")).isEqualTo("max-age=31536000 ; includeSubDomains");
    }

    @Test
    void FeatureSwitchesを持たないテストスライスでは素通しにする() throws Exception {
        FeatureSwitchFilter filter = new FeatureSwitchFilter(provider(null));

        assertPassesThrough(filter, "GET", "/api/lifecycle/cases");
        assertPassesThrough(filter, "GET", "/service-worker.js");
    }

    @Test
    void filterはSpringSecurityより前に動くServletFilterとして登録される() {
        Order order = AnnotationUtils.findAnnotation(FeatureSwitchFilter.class, Order.class);

        assertThat(AnnotationUtils.findAnnotation(FeatureSwitchFilter.class, Component.class)).isNotNull();
        assertThat(order).isNotNull();
        assertThat(order.value()).isEqualTo(FeatureSwitchFilter.ORDER);
        assertThat(FeatureSwitchFilter.ORDER).isLessThan(SecurityProperties.DEFAULT_FILTER_ORDER);
        // CorrelationIdFilter（HIGHEST_PRECEDENCE）・PwaNoStoreFilter（+20）より後ろ。
        assertThat(FeatureSwitchFilter.ORDER).isGreaterThan(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 20);
    }

    @Test
    void 遮断時の404本文はCustomErrorControllerの404と区別できない() throws Exception {
        MockHttpServletRequest errorRequest = new MockHttpServletRequest("GET", "/error");
        errorRequest.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);
        errorRequest.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/does-not-exist");
        Object handled = new CustomErrorController().handleError(errorRequest, new ExtendedModelMap());
        ApiResult<?> genuine = (ApiResult<?>) ((ResponseEntity<?>) handled).getBody();

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter(allOff()).doFilter(new MockHttpServletRequest("GET", "/api/lifecycle/cases"), blocked,
                new MockFilterChain());
        JsonNode body = JSON.readTree(blocked.getContentAsString());

        assertThat(genuine).isNotNull();
        assertThat(body.get("code").asInt()).isEqualTo(genuine.getCode());
        assertThat(body.get("message").asText()).isEqualTo(genuine.getMessage());
    }

    private static List<String> samples(Feature feature) {
        return java.util.stream.Stream.concat(API_SAMPLES.get(feature).stream(), PAGE_SAMPLES.get(feature).stream())
                .toList();
    }

    private static FeatureSwitchFilter filter(FeatureSwitches switches) {
        return new FeatureSwitchFilter(provider(switches));
    }

    private static void assertPassesThrough(FeatureSwitchFilter filter, String method, String uri) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest(method, uri), response, chain);

        assertThat(chain.getRequest()).as("%s %s は素通しになる", method, uri).isNotNull();
        assertThat(response.getStatus()).as(uri).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).as(uri).isNull();
    }

    private static void assertBlocked(FeatureSwitchFilter filter, String uri) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("GET", uri), response, chain);

        assertThat(chain.getRequest()).as("%s は遮断される", uri).isNull();
        assertThat(response.getStatus()).as(uri).isEqualTo(404);
    }
}
