package com.ses.config.integrationhub;

import com.ses.mapper.IntegrationHubWebhookResourceScopeMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ExternalApiCurrentOwnershipH2Test {
    @Autowired JdbcTemplate jdbc;
    @Autowired IntegrationHubWebhookResourceScopeMapper ownership;

    @Test
    void 四種類の実SQLは現在tenantと法人を全関連行で確認する() {
        String marker = "own-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        long customer = insert("INSERT INTO m_customer(company_name,tenant_id,legal_entity_id) VALUES(?,'tenant-a',9)", marker);
        long engineer = insert("INSERT INTO t_engineer(full_name,employment_type,tenant_id,legal_entity_id) "
                + "VALUES(?,'正社員','tenant-a',9)", marker);
        long project = insert("INSERT INTO t_project(project_name,customer_id,legal_entity_id) VALUES(?,?,9)", marker, customer);
        long contract = insert("INSERT INTO t_contract(engineer_id,project_id,customer_id,start_date,selling_price,cost_price,legal_entity_id) "
                + "VALUES(?,?,?,'2026-11-01',100,50,9)", engineer, project, customer);
        long work = insert("INSERT INTO t_work_record(contract_id,work_month,actual_hours,status) VALUES(?,'2026-11',160,'確定')", contract);
        long invoice = insert("INSERT INTO t_invoice(invoice_no,customer_id,billing_month,subtotal,tax,total,legal_entity_id) "
                + "VALUES(?,?,'2026-11',100,10,110,9)", marker, customer);
        insert("INSERT INTO t_invoice_item(invoice_id,work_record_id,amount) VALUES(?,?,110)", invoice, work);
        Map<String, Long> targets = Map.of("engineer-availability", engineer, "project", project,
                "contract-status", contract, "invoice-status", invoice);
        targets.forEach((type, id) -> {
            assertThat(ownership.isCurrentOwnership(type, id, "tenant-a", 9L)).as(type).isTrue();
            assertThat(ownership.isCurrentOwnership(type, id, "tenant-b", 9L)).as(type).isFalse();
            assertThat(ownership.isCurrentOwnership(type, id, "tenant-a", 10L)).as(type).isFalse();
        });
        jdbc.update("UPDATE t_engineer SET tenant_id='tenant-b' WHERE id=?", engineer);
        assertThat(ownership.isCurrentOwnership("contract-status", contract, "tenant-a", 9L)).isFalse();
        assertThat(ownership.isCurrentOwnership("invoice-status", invoice, "tenant-a", 9L)).isFalse();
        jdbc.update("UPDATE t_engineer SET tenant_id='tenant-a',legal_entity_id=10 WHERE id=?", engineer);
        assertThat(ownership.isCurrentOwnership("contract-status", contract, "tenant-a", 9L)).isFalse();
        assertThat(ownership.isCurrentOwnership("invoice-status", invoice, "tenant-a", 9L)).isFalse();
        jdbc.update("UPDATE m_customer SET tenant_id='tenant-b' WHERE id=?", customer);
        assertThat(ownership.isCurrentOwnership("project", project, "tenant-a", 9L)).isFalse();
    }

    private long insert(String sql, Object... args) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, key);
        return key.getKey().longValue();
    }
}
