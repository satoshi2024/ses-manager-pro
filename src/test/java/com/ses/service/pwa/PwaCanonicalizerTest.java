package com.ses.service.pwa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PwaCanonicalizerTest {

    private final PwaCanonicalizer canonicalizer = new PwaCanonicalizer(new ObjectMapper());

    @Test
    void objectのkey順が違っても同じhashになる() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode first = mapper.createObjectNode().put("remarks", "休暇").put("contractId", 10);
        ObjectNode second = mapper.createObjectNode().put("contractId", 10).put("remarks", "休暇");

        assertThat(canonicalizer.hash("timesheet", "2026-08", 3, first))
                .isEqualTo(canonicalizer.hash("timesheet", "2026-08", 3, second));
    }

    @Test
    void array順とbaseVersionはhash対象になる() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode payload = mapper.createObjectNode();
        payload.putArray("breaks").add(1).add(2);

        assertThat(canonicalizer.hash("attendance", "2026-08", 1, payload))
                .isNotEqualTo(canonicalizer.hash("attendance", "2026-08", 2, payload));
    }
}
