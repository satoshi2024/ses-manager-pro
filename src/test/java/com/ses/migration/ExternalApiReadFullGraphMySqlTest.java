package com.ses.migration;

import com.ses.mapper.ExternalApiReadMapper;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * NF05: H2ではなくMySQL 8の実行SQLで、customerからinvoiceまでの完全join graphを検証する。
 * 法人不一致・NULL・論理削除はSQL境界で除外し、結果後のJava filterには依存しない。
 */
@Tag("mysql")
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ExternalApiReadFullGraphMySqlTest {
    private static final long E1 = 7101L;
    private static final long E2 = 7102L;

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_nf05_full_graph")
            .withUsername("root")
            .withPassword("ses");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ExternalApiReadMapper readMapper;

    @Test
    void E1要求は完全joinGraphのlist_count_cursor_softDeleteでE2とNULLを返さない() {
        insertGraph();
        List<Long> engineerIds = List.of(981011L, 981012L, 981013L);
        List<Long> projectIds = List.of(981021L, 981022L, 981023L, 981024L, 981025L);
        List<Long> contractIds = List.of(981031L, 981032L, 981033L, 981034L, 981035L, 981036L, 981037L);
        List<Long> invoiceIds = List.of(981041L, 981042L, 981043L, 981044L, 981045L);

        var engineers = readMapper.selectEngineers(engineerIds, null, 100, E1);
        assertEquals(Set.of(981011L), ids(engineers));
        assertEquals(1, readMapper.countEngineers(engineerIds, E1));

        var projects = readMapper.selectProjects(projectIds, null, null, 100, E1);
        assertEquals(Set.of(981021L), ids(projects));
        assertEquals(1, readMapper.countProjects(projectIds, null, E1));
        assertEquals(Set.of(981021L), ids(readMapper.selectProjects(projectIds, null, 981022L, 100, E1)),
                "cursor継続も同じ法人predicateを使うこと");

        var contracts = readMapper.selectContracts(contractIds, null, null, 100, E1);
        assertEquals(Set.of(981031L, 981032L), ids(contracts));
        assertEquals(2, readMapper.countContracts(contractIds, null, E1));

        var invoices = readMapper.selectInvoices(invoiceIds, null, null, null, 100, E1);
        assertEquals(Set.of(981041L), ids(invoices));
        assertEquals(1, readMapper.countInvoices(invoiceIds, null, null, E1));
        assertEquals(2L, invoices.get(0).getContractCount(), "複数contract invoiceは単一IDへ縮退しない");
        assertNull(invoices.get(0).getContractId());

        jdbc.update("UPDATE t_engineer SET deleted_flag = 1 WHERE id = 981011");
        assertFalse(readMapper.selectEngineers(engineerIds, null, 100, E1).stream()
                .anyMatch(row -> row.getId().equals(981011L)), "soft-delete行を返さない");
        assertEquals(0, readMapper.countEngineers(engineerIds, E1));
    }

    private void insertGraph() {
        jdbc.update("DELETE FROM t_invoice_item WHERE invoice_id BETWEEN 981041 AND 981045");
        jdbc.update("DELETE FROM t_invoice WHERE id BETWEEN 981041 AND 981045");
        jdbc.update("DELETE FROM t_work_record WHERE id BETWEEN 981061 AND 981067");
        jdbc.update("DELETE FROM t_contract WHERE id BETWEEN 981031 AND 981037");
        jdbc.update("DELETE FROM t_project WHERE id BETWEEN 981021 AND 981025");
        jdbc.update("DELETE FROM t_engineer WHERE id BETWEEN 981011 AND 981013");
        jdbc.update("DELETE FROM m_customer WHERE id BETWEEN 981001 AND 981003");

        jdbc.update("INSERT INTO m_customer (id, legal_entity_id, company_name, deleted_flag) VALUES "
                + "(981001,7101,'NF05 E1 customer',0),(981002,7102,'NF05 E2 customer',0),(981003,NULL,'NF05 NULL customer',0)");
        jdbc.update("INSERT INTO t_engineer (id, legal_entity_id, full_name, employment_type, status, available_date, deleted_flag) VALUES "
                + "(981011,7101,'NF05 E1 engineer','正社員','Bench','2026-08-01',0),"
                + "(981012,7102,'NF05 E2 engineer','正社員','Bench','2026-08-01',0),"
                + "(981013,NULL,'NF05 NULL engineer','正社員','Bench','2026-08-01',0)");
        jdbc.update("INSERT INTO t_project (id, legal_entity_id, project_name, customer_id, status, deleted_flag) VALUES "
                + "(981021,7101,'NF05 E1 project',981001,'募集中',0),"
                + "(981022,7102,'NF05 E2 project',981002,'募集中',0),"
                + "(981023,7101,'NF05 NULL customer project',981003,'募集中',0),"
                + "(981024,7101,'NF05 deleted project',981001,'募集中',1),"
                + "(981025,NULL,'NF05 NULL project',981001,'募集中',0)");
        jdbc.update("INSERT INTO t_contract (id, legal_entity_id, contract_no, engineer_id, project_id, customer_id, contract_type, start_date, selling_price, cost_price, status, deleted_flag) VALUES "
                + "(981031,7101,'NF05-C-001',981011,981021,981001,'準委任','2026-08-01',100000,50000,'稼動中',0),"
                + "(981032,7101,'NF05-C-002',981011,981021,981001,'準委任','2026-08-01',100000,50000,'稼動中',0),"
                + "(981033,7102,'NF05-C-003',981012,981022,981002,'準委任','2026-08-01',100000,50000,'稼動中',0),"
                + "(981034,7101,'NF05-C-004',981011,981021,981002,'準委任','2026-08-01',100000,50000,'稼動中',0),"
                + "(981035,NULL,'NF05-C-005',981011,981021,981001,'準委任','2026-08-01',100000,50000,'稼動中',0),"
                + "(981036,7101,'NF05-C-006',981011,981024,981001,'準委任','2026-08-01',100000,50000,'稼動中',1),"
                + "(981037,7101,'NF05-C-007',981011,981025,981001,'準委任','2026-08-01',100000,50000,'稼動中',0)");
        jdbc.update("INSERT INTO t_work_record (id, contract_id, work_month, actual_hours, billing_amount, status) VALUES "
                + "(981061,981031,'2026-08',160,100000,'確定'),(981062,981032,'2026-08',160,100000,'確定'),"
                + "(981063,981033,'2026-08',160,100000,'確定'),(981064,981034,'2026-08',160,100000,'確定'),"
                + "(981065,981035,'2026-08',160,100000,'確定'),(981066,981036,'2026-08',160,100000,'確定'),"
                + "(981067,981037,'2026-08',160,100000,'確定')");
        jdbc.update("INSERT INTO t_invoice (id, invoice_no, legal_entity_id, customer_id, billing_month, subtotal, tax, total, status, issued_date, deleted_flag) VALUES "
                + "(981041,'NF05-I-001',7101,981001,'2026-08',200000,20000,220000,'未送付','2026-08-31',0),"
                + "(981042,'NF05-I-002',7101,981002,'2026-08',100000,10000,110000,'未送付','2026-08-31',0),"
                + "(981043,'NF05-I-003',7101,981003,'2026-08',100000,10000,110000,'未送付','2026-08-31',0),"
                + "(981044,'NF05-I-004',7101,981001,'2026-08',100000,10000,110000,'未送付','2026-08-31',0),"
                + "(981045,'NF05-I-005',NULL,981001,'2026-08',100000,10000,110000,'未送付','2026-08-31',0)");
        jdbc.update("INSERT INTO t_invoice_item (invoice_id, work_record_id, description, amount) VALUES "
                + "(981041,981061,'NF05 visible item',100000),(981041,981062,'NF05 second item',100000),"
                + "(981042,981063,'NF05 E2 item',100000),(981043,981065,'NF05 NULL customer item',100000),"
                + "(981044,981064,'NF05 mismatch item',100000)");
    }

    private static Set<Long> ids(List<com.ses.dto.integrationhub.ExternalApiReadRow> rows) {
        return rows.stream().map(com.ses.dto.integrationhub.ExternalApiReadRow::getId).collect(Collectors.toSet());
    }
}
