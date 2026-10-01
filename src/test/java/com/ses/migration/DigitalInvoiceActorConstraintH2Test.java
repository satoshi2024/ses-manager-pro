package com.ses.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** V182の監査主体pair制約をfast gateのH2でも検証する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DigitalInvoiceActorConstraintH2Test {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void digitalInvoice_rejectsInvalidHumanAndSystemActorPairs() {
        assertThrows(DataIntegrityViolationException.class, () -> insertInvoice(
                "HUMAN", "MANUAL_API", null));
        assertThrows(DataIntegrityViolationException.class, () -> insertInvoice(
                "SYSTEM", "SCHEDULER_POLL", 1L));
        assertDoesNotThrow(() -> insertInvoice("PROVIDER", "PROVIDER_CALLBACK", null));
    }

    @Test
    void digitalInvoiceEvent_rejectsInvalidActorPair() {
        long invoiceId = insertInvoice("SYSTEM", "SCHEDULER_POLL", null);

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
                INSERT INTO t_digital_invoice_event
                    (tenant_id, legal_entity_id, digital_invoice_id, provider_event_id,
                     event_type, event_at, payload_hash, signature_valid,
                     actor_type, confirmation_source, human_user_id)
                VALUES ('default', 1, ?, ?, 'DELIVERED', ?, ?, 1,
                        'PROVIDER', 'PROVIDER_CALLBACK', 1)
                """, invoiceId, "evt-" + UUID.randomUUID(), LocalDateTime.now(), "a".repeat(64)));
    }

    private long insertInvoice(String actorType, String confirmationSource, Long humanUserId) {
        String messageId = "msg-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO t_digital_invoice
                    (tenant_id, legal_entity_id, direction, profile, specification_version,
                     message_id, status, actor_type, confirmation_source, human_user_id)
                VALUES ('default', 1, 'RECEIVE', 'JP_PINT', '1.0', ?, 'RECEIVED', ?, ?, ?)
                """, messageId, actorType, confirmationSource, humanUserId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM t_digital_invoice WHERE message_id = ?", Long.class, messageId);
    }
}
