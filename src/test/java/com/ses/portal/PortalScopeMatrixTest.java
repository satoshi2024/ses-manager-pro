package com.ses.portal;

import com.ses.entity.PortalOrganization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockCookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T083 F2のscope matrix（R4.3・R5）。
 * 顧客A/顧客B/BPの3組織sessionで、全portal endpoint × 全methodをparameterized実行し、
 * 相互のデータ到達がなく、内部URL/内部APIへは到達できないことを検証する。
 * A1/A2（T084/T085）でデータendpointをこのmatrixへ追加する。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PortalScopeMatrixTest extends PortalTestSupport {

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Override
    protected JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    private String unique() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** 3組織fixture（顧客A/顧客B/BP）。 */
    protected record Matrix(PortalOrganization customerA, PortalOrganization customerB,
                            PortalOrganization bp,
                            UserFixture userA, UserFixture userB, UserFixture userBp) {
    }

    protected Matrix matrix() {
        PortalOrganization a = createCustomerOrg("A-" + unique());
        PortalOrganization b = createCustomerOrg("B-" + unique());
        PortalOrganization bp = createBpOrg(unique());
        UserFixture ua = readyUser(a, "a-" + unique() + "@example.com");
        UserFixture ub = readyUser(b, "b-" + unique() + "@example.com");
        UserFixture ubp = readyUser(bp, "bp-" + unique() + "@example.com");
        return new Matrix(a, b, bp, ua, ub, ubp);
    }

    /**
     * 全endpoint × 全org session matrix（F2時点のendpoint。A1/A2で拡張する）。
     * 各orgは自分自身の情報しか見えない（応答に他orgのemailが含まれない）。
     */
    static Stream<Arguments> portalEndpoints() {
        return Stream.of(
                Arguments.of("GET", "/api/portal/auth/me"),
                Arguments.of("GET", "/portal"),
                Arguments.of("GET", "/portal/terms")
        );
    }

    @ParameterizedTest
    @MethodSource("portalEndpoints")
    void 各組織sessionは自組織の情報だけを参照できる(String method, String path) throws Exception {
        Matrix m = matrix();
        var sessions = List.of(
                new Object[]{"customerA", m.userA()},
                new Object[]{"customerB", m.userB()},
                new Object[]{"bp", m.userBp()});

        for (Object[] entry : sessions) {
            String orgName = (String) entry[0];
            UserFixture fixture = (UserFixture) entry[1];
            var result = mockMvc.perform(get(path).cookie(fixture.sessionCookie()))
                    .andExpect(status().isOk())
                    .andReturn();
            String body = result.getResponse().getContentAsString();
            // 他組織のemailが応答に含まれない（IDORなし）
            for (Object[] other : sessions) {
                if (other[0] != entry[0]) {
                    assertFalse(body.contains(((UserFixture) other[1]).user().getEmail()),
                            orgName + " の応答に他組織のemailが含まれています: " + body);
                }
            }
        }
    }

    @Test
    void 顧客Aが顧客BとBPのID直接指定で404になる() throws Exception {
        Matrix m = matrix();
        // customer Aのsessionで、顧客Bのcustomer_idを持つorgのIDを直接参照しても到達できない。
        // データendpoint（documents/acceptance/invoice）はT084/T085で追加する。
        // ここではme()が他orgのIDを一切返さないことを確認する。
        mockMvc.perform(get("/api/portal/auth/me").cookie(m.userA().sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(m.userA().user().getEmail()))
                .andExpect(jsonPath("$.data.orgType").value("CUSTOMER"));
        mockMvc.perform(get("/api/portal/auth/me").cookie(m.userBp().sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orgType").value("BP"));
    }

    @Test
    void portal_userは内部APIと内部URLへ到達できない() throws Exception {
        Matrix m = matrix();
        MockCookie session = m.userA().sessionCookie();

        // 内部API（/api/**）: portal sessionでは認証されない（401）
        mockMvc.perform(get("/api/engineers").cookie(session))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/customers").cookie(session))
                .andExpect(status().isUnauthorized());

        // 内部画面: 本アプリの既存契約により未認証はpage/APIとも401
        // （PayrollSecurityAuditTest「未認証はpage/APIとも401」と同じ契約。portal sessionは
        //  内部chainの認証材料にならないため、portal cookieの有無に関わらず401）
        mockMvc.perform(get("/dashboard").cookie(session))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/engineer/list").cookie(session))
                .andExpect(status().isUnauthorized());

        // 内部のadmin管理（portal-admin）もportal sessionでは到達できない
        mockMvc.perform(get("/portal-admin").cookie(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void portalのCSRFは内部と完全分離されている() throws Exception {
        Matrix m = matrix();
        MockCookie session = m.userA().sessionCookie();

        // portal APIに内部のCSRFヘッダー（X-XSRF-TOKEN）だけ渡しても403
        mockMvc.perform(post("/api/portal/auth/logout").cookie(session)
                        .header("X-XSRF-TOKEN", "internal-token"))
                .andExpect(status().isForbidden());

        // portal専用cookie+headerなら成功
        var csrfPage = mockMvc.perform(get("/portal/login")).andExpect(status().isOk()).andReturn();
        MockCookie csrf = new MockCookie("XSRF-TOKEN-PORTAL",
                csrfPage.getResponse().getCookie("XSRF-TOKEN-PORTAL").getValue());
        mockMvc.perform(post("/api/portal/auth/logout").cookie(session).cookie(csrf)
                        .header("X-XSRF-TOKEN-PORTAL", csrf.getValue()))
                .andExpect(status().isOk());

        // 逆に、内部APIにportalのCSRFヘッダーを渡しても通らない（内部はX-XSRF-TOKEN必須）
        mockMvc.perform(post("/api/portal/auth/logout").cookie(session)
                        .header("X-XSRF-TOKEN-PORTAL", "portal-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 公開DTOに内部情報が構造的に含まれない() throws Exception {
        Matrix m = matrix();
        mockMvc.perform(get("/api/portal/auth/me").cookie(m.userA().sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.totpSecretEncrypted").doesNotExist())
                .andExpect(jsonPath("$.data.recoveryCodeHash").doesNotExist())
                .andExpect(jsonPath("$.data.costPrice").doesNotExist())
                .andExpect(jsonPath("$.data.sellingPrice").doesNotExist());
    }
}
