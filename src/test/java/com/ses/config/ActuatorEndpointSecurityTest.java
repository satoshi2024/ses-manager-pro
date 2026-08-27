package com.ses.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Actuator エンドポイント公開範囲の検証（ACC-OPS-P0-001）。
 *
 * 期待する不変条件:
 *   - liveness / readiness プローブは匿名でアクセスでき UP を返す。
 *   - health は匿名でアクセスできるが status のみ（component 等の内部詳細を返さない）。
 *   - env / beans は公開されない（exposure 未登録のため管理者でも 404）。匿名でも 200 にならない。
 *   - 既存の業務セキュリティに回帰がない（業務APIの認可・匿名拒否が従来どおり）。
 *
 * 実 K8s への配線（Deployment probe / 別 management port 等）は本テスト範囲外で BLOCKED。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ActuatorEndpointSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void liveness_匿名でアクセス可能でUPを返す() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void readiness_匿名でアクセス可能でUPを返す() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void health_匿名で最小情報のみ返す_内部詳細を漏らさない() throws Exception {
        // 集約 health は mail 等の indicator 次第で UP(200) / DOWN(503) になり得る（ALB は readiness のみ）。
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(200),
                        org.hamcrest.Matchers.is(503))))
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void env_管理者でも公開されない() throws Exception {
        // exposure.include は health,prometheus のため /actuator/env は未マッピング（404）
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "管理者")
    void beans_管理者でも公開されない() throws Exception {
        mockMvc.perform(get("/actuator/beans"))
                .andExpect(status().isNotFound());
    }

    @Test
    void env_匿名では200にならない() throws Exception {
        // 匿名は permitAll 対象外のため 200 では到達できない（認可でブロックされる）
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().is(org.hamcrest.Matchers.not(200)));
    }

    @Test
    void configprops_匿名では200にならない() throws Exception {
        mockMvc.perform(get("/actuator/configprops"))
                .andExpect(status().is(org.hamcrest.Matchers.not(200)));
    }

    @Test
    @WithMockUser(roles = "営業")
    void 回帰_業務APIは従来どおり認可される() throws Exception {
        mockMvc.perform(get("/api/autocomplete/engineers"))
                .andExpect(status().isOk());
    }

    @Test
    void 回帰_未認証の業務APIは401を返す() throws Exception {
        mockMvc.perform(get("/api/engineers"))
                .andExpect(status().isUnauthorized());
    }
}
