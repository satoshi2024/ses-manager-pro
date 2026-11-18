package com.ses.migration;

import com.ses.test.MySQLContainer;
import com.ses.test.MySqlTestImages;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V188の生成列・NULL一意性・FK RESTRICT・照合順序を実MySQLで固定する。 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
@Transactional
class PortalEngineerLinkSchemaMySqlTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MySqlTestImages.image())
            .withDatabaseName("g5_portal_link").withUsername("root").withPassword("ses");
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.sql.init.mode", () -> "never");
    }
    @Autowired private JdbcTemplate jdbc;

    @Test
    void 有効な紐付けは一人一件で解除後は同じ要員へ再登録できる() {
        Fixture f = fixture();
        long first = link(f);
        assertThat(active(first)).isEqualTo(f.user());
        assertThatThrownBy(() -> link(f)).isInstanceOf(DuplicateKeyException.class);
        jdbc.update("UPDATE t_portal_user_engineer_link SET released_at = '2026-11-14 12:00:00' WHERE id = ?", first);
        assertThat(active(first)).isNull();
        long second = link(f);
        assertThat(active(second)).isEqualTo(f.user());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_portal_user_engineer_link WHERE portal_user_id = ?"
                + " AND active_portal_user_id IS NOT NULL", Integer.class, f.user())).isEqualTo(1);
    }

    @Test
    void 論理削除と解除のNULL生成値は複数保存でき再登録を妨げない() {
        Fixture f = fixture();
        long deleted = link(f);
        jdbc.update("UPDATE t_portal_user_engineer_link SET deleted_flag = 1 WHERE id = ?", deleted);
        long released = link(f);
        jdbc.update("UPDATE t_portal_user_engineer_link SET released_at = '2026-11-14 12:00:00' WHERE id = ?", released);
        long current = link(f);
        assertThat(active(deleted)).isNull();
        assertThat(active(released)).isNull();
        assertThat(active(current)).isEqualTo(f.user());
    }

    @Test
    void 紐付けの参照先は削除もID更新もRESTRICTする() {
        Fixture f = fixture();
        link(f);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM t_portal_user WHERE id = ?", f.user()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE t_portal_user SET id = ? WHERE id = ?", f.user() + 10000000, f.user()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM t_engineer WHERE id = ?", f.engineer()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE t_engineer SET id = ? WHERE id = ?", f.engineer() + 10000000, f.engineer()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 必須参照のNULLと存在しない参照先を受け付けない() {
        Fixture f = fixture();
        for (Object[] ids : new Object[][]{{null, f.engineer()}, {f.user(), null}, {-1L, f.engineer()}, {f.user(), -1L}}) {
            assertThatThrownBy(() -> insert("INSERT INTO t_portal_user_engineer_link(tenant_id, portal_user_id,"
                    + " engineer_id, linked_by, linked_at) VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP)", f.tenant(), ids[0], ids[1]))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> insert("INSERT INTO t_portal_user_engineer_link(tenant_id, portal_user_id,"
                + " engineer_id, linked_by, linked_at) VALUES (NULL, ?, ?, 1, CURRENT_TIMESTAMP)", f.user(), f.engineer()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void tenant照合とportalメールの一意性は宣言された大文字小文字非区別に従う() {
        Fixture f = fixture();
        long id = link(f);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_portal_user_engineer_link WHERE id = ? AND tenant_id = ?",
                Integer.class, id, f.tenant().toUpperCase(java.util.Locale.ROOT))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()"
                + " AND TABLE_NAME = 't_portal_user_engineer_link' AND COLUMN_NAME = 'tenant_id'", String.class))
                .isEqualTo("utf8mb4_unicode_ci");
        assertThatThrownBy(() -> insert("INSERT INTO t_portal_user(portal_org_id, email) VALUES (?, ?)",
                f.organization(), f.email().toUpperCase(java.util.Locale.ROOT))).isInstanceOf(DuplicateKeyException.class);
    }

    private Long active(long id) {
        return jdbc.queryForObject("SELECT active_portal_user_id FROM t_portal_user_engineer_link WHERE id = ?", Long.class, id);
    }
    private long link(Fixture f) {
        return insert("INSERT INTO t_portal_user_engineer_link(tenant_id, portal_user_id, engineer_id, linked_by, linked_at)"
                + " VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP)", f.tenant(), f.user(), f.engineer());
    }
    private Fixture fixture() {
        String tenant = "g5-link-" + UUID.randomUUID();
        String email = "g5-" + UUID.randomUUID() + "@example.invalid";
        long customer = insert("INSERT INTO m_customer(company_name, tenant_id) VALUES ('紐付け検証顧客', ?)", tenant);
        long engineer = insert("INSERT INTO t_engineer(full_name, employment_type, tenant_id) VALUES ('紐付け検証要員', '正社員', ?)", tenant);
        long organization = insert("INSERT INTO m_portal_organization(tenant_id, type, customer_id) VALUES (?, 'CUSTOMER', ?)", tenant, customer);
        long user = insert("INSERT INTO t_portal_user(portal_org_id, email) VALUES (?, ?)", organization, email);
        return new Fixture(tenant, email, user, engineer, organization);
    }
    private long insert(String sql, Object... args) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < args.length; index++) statement.setObject(index + 1, args[index]);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }
    private record Fixture(String tenant, String email, long user, long engineer, long organization) { }
}
