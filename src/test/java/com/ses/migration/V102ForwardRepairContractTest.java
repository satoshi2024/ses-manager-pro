package com.ses.migration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G2-MIG-12〜16: V102をgit revertで戻すのではなく、partial/failed-historyから
 * metadataを見てforward repairできる契約を静的に検査する。
 */
class V102ForwardRepairContractTest {

    @Test
    void V102はpartial_shape_index_fk_triggerをmetadata収束する() throws Exception {
        String sql = new ClassPathResource("db/migration/V102__dispatch_compliance_g2_gate_schema.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(sql.contains("CREATE PROCEDURE __ses_g2_assert_shape"));
        assertTrue(sql.contains("G2_V102_SHAPE_MISMATCH forward repair required"));
        assertTrue(sql.contains("CREATE PROCEDURE __ses_g2_assert_column"));
        assertTrue(sql.contains("G2_V102_COLUMN_SHAPE_MISMATCH forward repair required"));
        assertTrue(sql.contains("CREATE PROCEDURE __ses_g2_create_index"));
        assertTrue(sql.contains("information_schema.statistics"));
        assertTrue(sql.contains("CREATE PROCEDURE __ses_g2_repair_fk"));
        assertTrue(sql.contains("CREATE PROCEDURE __ses_g2_repair_delivery_fk"));
        assertTrue(sql.contains("information_schema.table_constraints"));
        assertTrue(sql.contains("information_schema.COLUMNS"));
        assertTrue(sql.contains("DROP TRIGGER IF EXISTS trg_g2_mapping_slot_check"));
        assertTrue(sql.contains("DROP TRIGGER IF EXISTS trg_g2_operation_no_delete"));
        assertTrue(sql.contains("CREATE TRIGGER trg_g2_mapping_source_freeze_insert"));
        assertTrue(sql.contains("CREATE TRIGGER trg_g2_assignment_slot_check"));
    }

    @Test
    void V102は適用後rollbackをforward_repairとして扱う() throws Exception {
        String sql = new ClassPathResource("db/migration/V102__dispatch_compliance_g2_gate_schema.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        assertTrue(sql.contains("forward repair"));
        assertTrue(sql.contains("DROP PROCEDURE IF EXISTS __ses_g2_assert_shape"));
        assertTrue(sql.contains("DROP PROCEDURE IF EXISTS __ses_g2_repair_fk"));
    }
}
