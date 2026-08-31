package com.ses.migration;

import com.ses.config.integrationhub.ExternalApiEffectiveScope;
import com.ses.config.integrationhub.ExternalApiPrincipal;
import com.ses.config.integrationhub.ExternalApiPublicIdCodec;
import com.ses.config.integrationhub.ExternalApiSecurityException;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.dto.integrationhub.ExternalApiReadRow;
import com.ses.mapper.ExternalApiReadMapper;
import com.ses.service.integrationhub.ExternalApiReadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** M: client A/Bのscope境界と存在秘匿（penetration）を実SQL/service経路で固定する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IntegrationHubMPenetrationTest {
    private static final long CUSTOMER_A = 9910001L;
    private static final long CUSTOMER_B = 9910002L;
    private static final long PROJECT_A = 9910011L;
    private static final long PROJECT_B = 9910012L;

    @Autowired
    private ExternalApiReadMapper mapper;
    @Autowired
    private ExternalApiReadService readService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void clientAはclientBのprojectをcountにもlistにも観測できない() {
        insertCustomersAndProjects();

        List<ExternalApiReadRow> rows = mapper.selectProjects(
                List.of(PROJECT_A, PROJECT_B), List.of(CUSTOMER_A), null, 10);
        assertEquals(List.of(PROJECT_A), rows.stream().map(ExternalApiReadRow::getId).toList());
        assertEquals(1, mapper.countProjects(List.of(PROJECT_A, PROJECT_B), List.of(CUSTOMER_A)));
    }

    @Test
    void outOfScopeOpaqueIdはinScopeと同じnull応答で存在を推測できない() {
        insertCustomersAndProjects();
        ExternalApiPrincipal clientA = principal("client-a", "tenant-a", 71L,
                "{\"tenantIds\":[\"tenant-a\"],\"legalEntityIds\":[\"71\"],"
                        + "\"projectIds\":[\"" + PROJECT_A + "\"],\"customerIds\":[\"" + CUSTOMER_A + "\"]}");
        ExternalApiEffectiveScope scopeA = scope("tenant-a", 71L, Map.of(
                "tenantIds", Set.of("tenant-a"),
                "legalEntityIds", Set.of("71"),
                "projectIds", Set.of(String.valueOf(PROJECT_A)),
                "customerIds", Set.of(String.valueOf(CUSTOMER_A))));

        ExternalApiPublicIdCodec codec = codec();
        String inScope = codec.encode(clientA, "project", PROJECT_A);
        String outOfScope = codec.encode(clientA, "project", PROJECT_B);
        String invalid = "not-a-valid-public-id";

        assertEquals("募集中", readService.getProject(clientA, scopeA, inScope).status());
        assertNull(readService.getProject(clientA, scopeA, outOfScope));
        assertNull(readService.getProject(clientA, scopeA, invalid));
    }

    @Test
    void malformedScopeはquery前にfailClosedし他clientのrow数を漏らさない() {
        ExternalApiPrincipal clientA = principal("client-a", "tenant-a", 71L,
                "{\"tenantIds\":[\"tenant-a\"],\"legalEntityIds\":[\"71\"],"
                        + "\"projectIds\":[\"not-an-internal-id\"]}");
        ExternalApiEffectiveScope malformed = scope("tenant-a", 71L, Map.of(
                "tenantIds", Set.of("tenant-a"),
                "legalEntityIds", Set.of("71"),
                "projectIds", Set.of("not-an-internal-id")));

        assertThrows(ExternalApiSecurityException.class,
                () -> readService.listProjects(clientA, malformed, 10, null));
    }

    private void insertCustomersAndProjects() {
        jdbcTemplate.update("INSERT INTO m_customer (id, company_name) VALUES (?, ?), (?, ?)",
                CUSTOMER_A, "m-penetration-a", CUSTOMER_B, "m-penetration-b");
        jdbcTemplate.update("""
                INSERT INTO t_project (id, project_name, customer_id, status, start_date, end_date, deleted_flag)
                VALUES (?, ?, ?, ?, ?, ?, 0), (?, ?, ?, ?, ?, ?, 0)
                """, PROJECT_A, "project-a", CUSTOMER_A, "募集中",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                PROJECT_B, "project-b", CUSTOMER_B, "募集中",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
    }

    private static ExternalApiPrincipal principal(String clientId, String tenantId, long legalEntityId,
                                                  String dataScopeJson) {
        return new ExternalApiPrincipal(clientId, 700L, tenantId, legalEntityId, dataScopeJson, 1, "m-key", "STANDARD");
    }

    private static ExternalApiEffectiveScope scope(String tenantId, long legalEntityId,
                                                   Map<String, Set<String>> allowed) {
        return new ExternalApiEffectiveScope(tenantId, legalEntityId, allowed);
    }

    private static ExternalApiPublicIdCodec codec() {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getPublicApi().setPublicIdKey("test-integration-hub-public-id-key-at-least-32-bytes");
        return new ExternalApiPublicIdCodec(properties);
    }
}
