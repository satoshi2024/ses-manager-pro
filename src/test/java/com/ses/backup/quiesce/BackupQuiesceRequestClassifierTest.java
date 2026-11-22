package com.ses.backup.quiesce;

import com.ses.backup.quiesce.BackupQuiesceRequestClassifier.Classification;
import com.ses.backup.quiesce.BackupQuiesceRequestClassifier.Kind;
import com.ses.config.ProductionBootstrapMode;
import com.ses.service.security.BreakGlassService;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RB-QUIESCE BWQ-005: 静止中の HTTP の要求の分類（design.md §6.3・§6.4）を method × path × session の表で固定する。
 */
class BackupQuiesceRequestClassifierTest {

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource({
            // 更新の method（認証の前の login・password の変更・PWA の送信を含む）
            "POST,    /api/engineers,                            UPDATE_METHOD,   WRITE",
            "PUT,     /api/engineers/1,                          UPDATE_METHOD,   WRITE",
            "PATCH,   /api/engineers/1,                          UPDATE_METHOD,   WRITE",
            "DELETE,  /api/engineers/1,                          UPDATE_METHOD,   WRITE",
            "POST,    /login,                                    UPDATE_METHOD,   WRITE",
            "POST,    /password/change,                          UPDATE_METHOD,   WRITE",
            "POST,    /engineer/save,                            UPDATE_METHOD,   WRITE",
            "TRACE,   /api/engineers,                            UPDATE_METHOD,   WRITE",
            // 社内の logout だけが LOGOUT。portal の logout・GET /logout・似た path は対象外（U-8）
            "POST,    /logout,                                   LOGOUT,          LOGOUT",
            "POST,    /api/portal/auth/logout,                   UPDATE_METHOD,   WRITE",
            "POST,    /logout/x,                                 UPDATE_METHOD,   WRITE",
            "GET,     /logout,                                   READ,            READ",
            // 書込みを伴う既知の GET
            "GET,     /oauth2/authorization/corporate,           WRITING_GET,     WRITE",
            "GET,     /login/oauth2/code/corporate,              WRITING_GET,     WRITE",
            "GET,     /integrations/freee/callback,              WRITING_GET,     WRITE",
            "GET,     /integrations/freee,                       WRITING_GET,     WRITE",
            // download（U-10。ApiAuditFilter の判定）
            "GET,     /api/files/abc.pdf,                        DOWNLOAD,        WRITE",
            "GET,     /api/contracts/1/pdf/download,             DOWNLOAD,        WRITE",
            "GET,     /api/sales-orders/1/acknowledgement-pdf/download, DOWNLOAD, WRITE",
            "GET,     /api/my/change-requests/3/attachment,      DOWNLOAD,        WRITE",
            "HEAD,    /api/files/abc.pdf,                        READ,            READ",
            // 外部 API（U-6。全 method）
            "GET,     /external-api/v1/projects,                 EXTERNAL_API,    EXTERNAL",
            "POST,    /external-api/v1/webhooks/provider-a,      EXTERNAL_API,    EXTERNAL",
            "GET,     /external-api,                             EXTERNAL_API,    EXTERNAL",
            // 参照
            "GET,     /api/engineers,                            READ,            READ",
            "GET,     /engineer/list,                            READ,            READ",
            "GET,     /api/notifications,                        READ,            READ",
            "HEAD,    /dashboard,                                READ,            READ",
            "OPTIONS, /api/engineers,                            READ,            READ",
            // 静的資源と actuator（GET・HEAD だけ）
            "GET,     /css/common.css,                           STATIC_RESOURCE, ALWAYS",
            "HEAD,    /js/common.js,                             STATIC_RESOURCE, ALWAYS",
            "GET,     /lib/bootstrap/bootstrap.min.css,          STATIC_RESOURCE, ALWAYS",
            "GET,     /img/logo.png,                             STATIC_RESOURCE, ALWAYS",
            "GET,     /favicon.ico,                              STATIC_RESOURCE, ALWAYS",
            "POST,    /css/common.css,                           UPDATE_METHOD,   WRITE",
            "GET,     /actuator/health/readiness,                ACTUATOR,        ALWAYS",
            "GET,     /actuator/health,                          ACTUATOR,        ALWAYS",
            "GET,     /actuator/prometheus,                      ACTUATOR,        ALWAYS",
            "GET,     /actuator/metrics/jvm.memory.used,         ACTUATOR,        ALWAYS",
            "POST,    /actuator/health,                          UPDATE_METHOD,   WRITE",
            "GET,     /actuator/env,                             READ,            READ"
    })
    void methodとpathで分類する(String method, String path, Classification expected, Kind kind) {
        Classification actual = BackupQuiesceRequestClassifier.classify(method, path, path, false, false);

        assertThat(actual).isEqualTo(expected);
        assertThat(actual.kind()).isEqualTo(kind);
    }

    @ParameterizedTest(name = "特権の session の {0} {1} → {2}")
    @CsvSource({
            "GET,  /dashboard,      PRIVILEGED_SESSION",
            "GET,  /api/engineers,  PRIVILEGED_SESSION",
            "POST, /logout,         LOGOUT",
            "POST, /logout/x,       PRIVILEGED_SESSION",
            "GET,  /css/common.css, STATIC_RESOURCE",
            "GET,  /actuator/health, ACTUATOR"
    })
    void bootstrapとbreak_glassのsessionは静的資源とactuatorとlogout以外を書込みとして扱う(String method, String path,
                                                                        Classification expected) {
        assertThat(BackupQuiesceRequestClassifier.classify(method, path, path, false, true)).isEqualTo(expected);
    }

    @Test
    void error_dispatchは常に通す() {
        assertThat(BackupQuiesceRequestClassifier.classify("POST", "/api/engineers", "/api/engineers", true, true))
                .isEqualTo(Classification.ERROR_DISPATCH);
        assertThat(Classification.ERROR_DISPATCH.kind()).isEqualTo(Kind.ALWAYS);
    }

    @Test
    void 外部APIは生のURIでも照合し表記ゆれで外さない() {
        assertThat(BackupQuiesceRequestClassifier.classify("GET", "/external%2Dapi/v1/projects",
                "/external-api/v1/projects", false, false)).isEqualTo(Classification.EXTERNAL_API);
    }

    @Test
    void requestからsessionの属性と復号済みのpathを読む() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/api/engineers");
        request.setContextPath("/app");
        assertThat(BackupQuiesceRequestClassifier.classify(request)).isEqualTo(Classification.READ);

        MockHttpSession bootstrap = new MockHttpSession();
        bootstrap.setAttribute(ProductionBootstrapMode.SESSION_ATTRIBUTE, Boolean.TRUE);
        request.setSession(bootstrap);
        assertThat(BackupQuiesceRequestClassifier.classify(request)).isEqualTo(Classification.PRIVILEGED_SESSION);

        MockHttpServletRequest breakGlass = new MockHttpServletRequest("GET", "/dashboard");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(BreakGlassService.INCIDENT_ID_ATTRIBUTE, "INC-1");
        breakGlass.setSession(session);
        assertThat(BackupQuiesceRequestClassifier.classify(breakGlass)).isEqualTo(Classification.PRIVILEGED_SESSION);

        MockHttpServletRequest encoded = new MockHttpServletRequest("POST", "/logou%74");
        assertThat(BackupQuiesceRequestClassifier.classify(encoded)).isEqualTo(Classification.LOGOUT);

        MockHttpServletRequest error = new MockHttpServletRequest("POST", "/error");
        error.setDispatcherType(DispatcherType.ERROR);
        assertThat(BackupQuiesceRequestClassifier.classify(error)).isEqualTo(Classification.ERROR_DISPATCH);
    }
}
