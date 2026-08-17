package com.ses.mapper;

import com.ses.BaseIntegrationTest;
import com.ses.entity.BpPayment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BpPaymentMapperTest extends BaseIntegrationTest {

    @Autowired
    private BpPaymentMapper bpPaymentMapper;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    void testLayerOrderAndUniqueConstraint() {
        long suffix = System.nanoTime();
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES (?)", "BP_Cust_" + suffix);
        Long customerId = jdbcTemplate.queryForObject("SELECT id FROM m_customer WHERE company_name = ?", Long.class, "BP_Cust_" + suffix);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type) VALUES (?, '正社員')", "BP_Eng_" + suffix);
        Long engineerId = jdbcTemplate.queryForObject("SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "BP_Eng_" + suffix);
        jdbcTemplate.update("INSERT INTO t_project (customer_id, project_name, status) VALUES (?, ?, '募集中')", customerId, "BP_Proj_" + suffix);
        Long projectId = jdbcTemplate.queryForObject("SELECT id FROM t_project WHERE project_name = ?", Long.class, "BP_Proj_" + suffix);
        jdbcTemplate.update("INSERT INTO t_contract (contract_no, customer_id, project_id, engineer_id, contract_type, status, start_date, end_date, selling_price, cost_price) VALUES (?, ?, ?, ?, '準委任', '稼動中', '2026-08-01', '2026-08-31', 700000, 500000)",
                "CN-BP-" + suffix, customerId, projectId, engineerId);
        Long contractId = jdbcTemplate.queryForObject("SELECT id FROM t_contract WHERE contract_no = ?", Long.class, "CN-BP-" + suffix);
        jdbcTemplate.update("INSERT INTO t_work_record (contract_id, work_month, actual_hours, status) VALUES (?, '2026-08', 160.0, '確定')", contractId);
        Long workRecordId = jdbcTemplate.queryForObject("SELECT id FROM t_work_record WHERE contract_id = ?", Long.class, contractId);

        // Insert first layer
        BpPayment bp1 = new BpPayment();
        bp1.setWorkRecordId(workRecordId);
        bp1.setLayerOrder(1);
        bp1.setAmount(new BigDecimal("500000"));
        bp1.setPayeeCompanyName("Company A");
        bpPaymentMapper.insert(bp1);
        assertNotNull(bp1.getId());

        // Insert second layer
        BpPayment bp2 = new BpPayment();
        bp2.setWorkRecordId(workRecordId);
        bp2.setLayerOrder(2);
        bp2.setAmount(new BigDecimal("400000"));
        bp2.setPayeeCompanyName("Company B");
        bp2.setParentPaymentId(bp1.getId());
        bpPaymentMapper.insert(bp2);
        assertNotNull(bp2.getId());

        // Test Unique constraint
        BpPayment bp3 = new BpPayment();
        bp3.setWorkRecordId(workRecordId);
        bp3.setLayerOrder(1); // Same layer order
        bp3.setAmount(new BigDecimal("300000"));

        assertThrows(DuplicateKeyException.class, () -> {
            bpPaymentMapper.insert(bp3);
        });

        // Test selection ordered by layer
        List<BpPayment> payments = bpPaymentMapper.selectByWorkRecordIdOrderByLayer(workRecordId);
        assertEquals(2, payments.size());
        assertEquals(1, payments.get(0).getLayerOrder());
        assertEquals(2, payments.get(1).getLayerOrder());
    }
}
