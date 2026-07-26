package com.ses.common.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    @Test
    void businessException429_isReturnedAsTooManyRequests() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var response = handler.handleBusinessException(new BusinessException(429, "busy"));

        assertEquals(429, response.getStatusCode().value());
        assertEquals(429, response.getBody().getCode());
    }
}
