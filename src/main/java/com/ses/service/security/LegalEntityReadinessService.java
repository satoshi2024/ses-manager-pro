package com.ses.service.security;

import com.ses.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 法人backfill未解決行を公開API/Copilotの有効化前に検知するreadiness境界。 */
@Service
@RequiredArgsConstructor
public class LegalEntityReadinessService {
    private final JdbcTemplate jdbcTemplate;

    public void assertReady() {
        long unresolved = 0L;
        unresolved += count("SELECT COUNT(*) FROM m_customer WHERE deleted_flag = 0 AND legal_entity_id IS NULL");
        unresolved += count("SELECT COUNT(*) FROM t_engineer WHERE deleted_flag = 0 AND legal_entity_id IS NULL");
        unresolved += count("SELECT COUNT(*) FROM t_project WHERE deleted_flag = 0 AND legal_entity_id IS NULL");
        unresolved += count("SELECT COUNT(*) FROM t_contract WHERE deleted_flag = 0 AND legal_entity_id IS NULL");
        unresolved += count("SELECT COUNT(*) FROM t_invoice WHERE deleted_flag = 0 AND legal_entity_id IS NULL");
        // 後発のwrite入口もNULL法人を公開/AI境界へ流さない。
        unresolved += countIfTableExists("t_lead", "SELECT COUNT(*) FROM t_lead WHERE deleted_flag = 0 AND legal_entity_id IS NULL");
        unresolved += countIfTableExists("t_opportunity", "SELECT COUNT(*) FROM t_opportunity WHERE deleted_flag = 0 AND legal_entity_id IS NULL");
        unresolved += countIfTableExists("t_resume_ingestion", "SELECT COUNT(*) FROM t_resume_ingestion WHERE deleted_flag = 0 AND legal_entity_id IS NULL");
        unresolved += countIfTableExists("t_project_ingestion", "SELECT COUNT(*) FROM t_project_ingestion WHERE deleted_flag = 0 AND legal_entity_id IS NULL");
        unresolved += countIfTableExists("t_bp_availability", "SELECT COUNT(*) FROM t_bp_availability WHERE deleted_flag = 0 AND legal_entity_id IS NULL");
        unresolved += countIfTableExists("t_bp_availability_ingestion", "SELECT COUNT(*) FROM t_bp_availability_ingestion WHERE deleted_flag = 0 AND legal_entity_id IS NULL");
        unresolved += countIfTableExists("t_opportunity", """
                SELECT COUNT(*) FROM t_opportunity o JOIN m_customer c ON c.id = o.customer_id
                WHERE o.deleted_flag = 0 AND c.deleted_flag = 0
                  AND (o.legal_entity_id IS NULL OR c.legal_entity_id IS NULL
                       OR o.legal_entity_id <> c.legal_entity_id)
                """);
        unresolved += countIfTableExists("t_lead", """
                SELECT COUNT(*) FROM t_lead l JOIN m_customer c ON c.id = l.converted_customer_id
                WHERE l.deleted_flag = 0 AND c.deleted_flag = 0
                  AND l.converted_customer_id IS NOT NULL
                  AND (l.legal_entity_id IS NULL OR c.legal_entity_id IS NULL
                       OR l.legal_entity_id <> c.legal_entity_id)
                """);
        unresolved += countIfTableExists("t_resume_ingestion", """
                SELECT COUNT(*) FROM t_resume_ingestion r JOIN t_engineer e ON e.id = r.converted_engineer_id
                WHERE r.deleted_flag = 0 AND e.deleted_flag = 0
                  AND r.converted_engineer_id IS NOT NULL
                  AND (r.legal_entity_id IS NULL OR e.legal_entity_id IS NULL
                       OR r.legal_entity_id <> e.legal_entity_id)
                """);
        unresolved += countIfTableExists("t_project_ingestion", """
                SELECT COUNT(*) FROM t_project_ingestion r JOIN t_project p ON p.id = r.converted_project_id
                WHERE r.deleted_flag = 0 AND p.deleted_flag = 0
                  AND r.converted_project_id IS NOT NULL
                  AND (r.legal_entity_id IS NULL OR p.legal_entity_id IS NULL
                       OR r.legal_entity_id <> p.legal_entity_id)
                """);
        unresolved += countIfTableExists("t_bp_availability", """
                SELECT COUNT(*) FROM t_bp_availability b JOIN t_engineer e ON e.id = b.promoted_engineer_id
                WHERE b.deleted_flag = 0 AND e.deleted_flag = 0
                  AND b.promoted_engineer_id IS NOT NULL
                  AND (b.legal_entity_id IS NULL OR e.legal_entity_id IS NULL
                       OR b.legal_entity_id <> e.legal_entity_id)
                """);
        unresolved += count("""
                SELECT COUNT(*) FROM t_project p JOIN m_customer c ON c.id = p.customer_id
                WHERE p.deleted_flag = 0 AND c.deleted_flag = 0
                  AND (p.legal_entity_id IS NULL OR c.legal_entity_id IS NULL
                       OR p.legal_entity_id <> c.legal_entity_id)
                """);
        unresolved += count("""
                SELECT COUNT(*) FROM t_contract c
                JOIN t_project p ON p.id = c.project_id
                JOIN t_engineer e ON e.id = c.engineer_id
                JOIN m_customer cu ON cu.id = c.customer_id
                WHERE c.deleted_flag = 0 AND p.deleted_flag = 0 AND e.deleted_flag = 0 AND cu.deleted_flag = 0
                  AND (c.legal_entity_id IS NULL OR p.legal_entity_id IS NULL OR e.legal_entity_id IS NULL
                       OR cu.legal_entity_id IS NULL OR c.legal_entity_id <> p.legal_entity_id
                       OR c.legal_entity_id <> e.legal_entity_id OR c.legal_entity_id <> cu.legal_entity_id)
                """);
        unresolved += count("""
                SELECT COUNT(*) FROM t_invoice i JOIN m_customer c ON c.id = i.customer_id
                WHERE i.deleted_flag = 0 AND c.deleted_flag = 0
                  AND (i.legal_entity_id IS NULL OR c.legal_entity_id IS NULL
                       OR i.legal_entity_id <> c.legal_entity_id)
                """);
        if (unresolved > 0) {
            throw BusinessException.of(503, "LEGAL_ENTITY_BACKFILL_INCOMPLETE");
        }
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private long countIfTableExists(String table, String sql) {
        Long exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                Long.class, table);
        return exists != null && exists > 0 ? count(sql) : 0L;
    }
}
