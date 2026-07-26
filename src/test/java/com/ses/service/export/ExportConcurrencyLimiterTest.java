package com.ses.service.export;

import com.ses.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExportConcurrencyLimiterTest {

    @AfterEach
    void reset() {
        ExportConcurrencyLimiter.configure(2);
    }

    @Test
    void thirdExportIsRejectedBeforeStarting() {
        ExportConcurrencyLimiter.configure(2);
        ExportConcurrencyLimiter.acquire();
        ExportConcurrencyLimiter.acquire();

        BusinessException exception = assertThrows(BusinessException.class,
                ExportConcurrencyLimiter::acquire);

        assertEquals(429, exception.getCode());
        ExportConcurrencyLimiter.release();
        ExportConcurrencyLimiter.release();
    }
}
