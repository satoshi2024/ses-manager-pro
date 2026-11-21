package com.ses.mapper;

import com.ses.entity.BpPayment;
import com.ses.test.MySQLContainer;
import com.ses.test.MySqlTestImages;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.Statement;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** 実MySQLの工数→契約→顧客JOINでBP支払のtenant所有権を検証する。 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
@Transactional
class BpPaymentTenantSqlMySqlTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MySqlTestImages.image())
            .withDatabaseName("g5_bp_tenant").withUsername("root").withPassword("ses");
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.sql.init.mode", () -> "never");
    }
    @Autowired private BpPaymentMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void 自tenantの支払だけを単票候補および月次JOINから取得する() {
        String tenant = "bp-g5-" + UUID.randomUUID();
        long own = fixture(tenant, tenant, 0, 0, 0);
        long other = fixture("other-" + tenant, "other-" + tenant, 0, 0, 0);
        assertThat(mapper.selectByIdForTenant(own, tenant).getId()).isEqualTo(own);
        assertThat(mapper.selectByIdForTenant(other, tenant)).isNull();
        assertThat(mapper.selectSendCandidatesForTenant(tenant, 20)).extracting(BpPayment::getId).containsExactly(own);
        assertThat(mapper.selectListWithDetailsForTenant("2026-11", null, tenant))
                .extracting(row -> row.getId()).containsExactly(own);
        assertThat(mapper.selectByIdForTenant(own, null)).isNull();
        assertThat(mapper.selectSendCandidatesForTenant(null, 20)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"NULL,OWN,0,0,0", "OWN,NULL,0,0,0", "OWN,OTHER,0,0,0",
            "OWN,OWN,1,0,0", "OWN,OWN,0,1,0", "OWN,OWN,0,0,1"})
    void 所有権不明不一致および削除済みJOINはすべて不可視になる(
            String contractScope, String customerScope, int paymentDeleted, int contractDeleted, int customerDeleted) {
        String tenant = "bp-g5-" + UUID.randomUUID();
        long id = fixture(scope(contractScope, tenant), scope(customerScope, tenant),
                paymentDeleted, contractDeleted, customerDeleted);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_bp_payment WHERE id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(mapper.selectByIdForTenant(id, tenant)).isNull();
        assertThat(mapper.selectSendCandidatesForTenant(tenant, 20)).isEmpty();
        assertThat(mapper.selectListWithDetailsForTenant("2026-11", null, tenant)).isEmpty();
    }

    @Test
    void 候補状態と件数上限をtenant限定後に適用する() {
        String tenant = "bp-g5-" + UUID.randomUUID();
        long older = fixture(tenant, tenant, 0, 0, 0);
        long newer = fixture(tenant, tenant, 0, 0, 0);
        long draft = fixture(tenant, tenant, 0, 0, 0);
        jdbc.update("UPDATE t_bp_payment SET status = 'DRAFT' WHERE id = ?", draft);
        fixture("foreign-" + tenant, "foreign-" + tenant, 0, 0, 0);
        assertThat(mapper.selectSendCandidatesForTenant(tenant, 1)).extracting(BpPayment::getId).containsExactly(newer);
        assertThat(mapper.selectSendCandidatesForTenant(tenant, 20)).extracting(BpPayment::getId).containsExactly(newer, older);
        assertThat(mapper.selectByIdForTenant(draft, tenant)).isNotNull();
    }

    private String scope(String value, String own) { return "NULL".equals(value) ? null : "OWN".equals(value) ? own : "other-" + own; }
    private long fixture(String contractTenant, String customerTenant, int paymentDeleted, int contractDeleted, int customerDeleted) {
        long customer = insert("INSERT INTO m_customer(company_name, tenant_id, deleted_flag) VALUES ('BP境界検証', ?, ?)",
                customerTenant, customerDeleted);
        long engineer = insert("INSERT INTO t_engineer(full_name, employment_type, tenant_id) VALUES ('境界検証要員', '正社員', ?)",
                contractTenant);
        long project = insert("INSERT INTO t_project(project_name, customer_id) VALUES ('境界検証案件', ?)", customer);
        long contract = insert("INSERT INTO t_contract(contract_no, engineer_id, project_id, customer_id, start_date,"
                + " selling_price, cost_price, status, tenant_id, deleted_flag) VALUES (?, ?, ?, ?, '2026-11-01',"
                + " 100000, 70000, '稼動中', ?, ?)", "G5-" + UUID.randomUUID(), engineer, project, customer, contractTenant, contractDeleted);
        long work = insert("INSERT INTO t_work_record(contract_id, work_month, actual_hours, status) VALUES (?, '2026-11', 160, '確定')", contract);
        return insert("INSERT INTO t_bp_payment(work_record_id, amount, status, deleted_flag) VALUES (?, 70000, 'APPROVED', ?)", work, paymentDeleted);
    }

    private long insert(String sql, Object... arguments) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < arguments.length; index++) statement.setObject(index + 1, arguments[index]);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }
}
