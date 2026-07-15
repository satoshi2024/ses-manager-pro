package com.ses.mapper;

import com.ses.BaseIntegrationTest;
import com.ses.entity.BpPayment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BpPaymentMapperTest extends BaseIntegrationTest {

    @Autowired
    private BpPaymentMapper bpPaymentMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void testLayerOrderAndUniqueConstraint() {
        // t_work_record が必要かもしれないが、FKを無効化するか、あるいは事前にデータを入れるか。
        // engineer-schema-h2.sql の定義では:
        // CREATE TABLE t_bp_payment (
        //   id BIGINT AUTO_INCREMENT PRIMARY KEY,
        //   work_record_id BIGINT NOT NULL,
        //   layer_order INT NOT NULL DEFAULT 1, ...
        //   UNIQUE KEY uk_work_record_layer (work_record_id, layer_order)
        // );
        // H2の場合、fk_bp_payment_parent はあるが work_record_id のFKは H2スキーマ上は存在しない。(t_work_recordテーブル自体はある)

        Long workRecordId = 9999L;

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
