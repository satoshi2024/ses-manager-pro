package com.ses.service.servicedesk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.ServiceRequestSequence;
import com.ses.mapper.ServiceRequestSequenceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** NF-02のH2採番器がtenant/月単位で9999を超えないことを検証する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ServiceRequestSequenceH2Test {

    private static final String TENANT = "nf02-h2-sequence";
    private static final String MONTH = "202609";

    @Autowired
    private ServiceRequestSequenceMapper mapper;

    @BeforeEach
    void clean() {
        mapper.delete(new LambdaQueryWrapper<ServiceRequestSequence>()
                .eq(ServiceRequestSequence::getTenantId, TENANT)
                .eq(ServiceRequestSequence::getRequestMonth, MONTH));
    }

    @Test
    void tenant月行を一度だけ作り9999で停止する() {
        assertEquals(1, mapper.ensureRow(TENANT, MONTH));
        assertEquals(1, mapper.incrementIfAvailable(TENANT, MONTH));
        assertEquals(1, mapper.selectLastNumber(TENANT, MONTH));

        mapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ServiceRequestSequence>()
                .eq(ServiceRequestSequence::getTenantId, TENANT)
                .eq(ServiceRequestSequence::getRequestMonth, MONTH)
                .set(ServiceRequestSequence::getLastNumber, 9998));
        assertEquals(1, mapper.incrementIfAvailable(TENANT, MONTH));
        assertEquals(9999, mapper.selectLastNumber(TENANT, MONTH));
        assertEquals(0, mapper.incrementIfAvailable(TENANT, MONTH));
        assertEquals(9999, mapper.selectLastNumber(TENANT, MONTH));
    }
}
