package com.ses.service.security;

import jakarta.servlet.http.HttpServletRequest;

public interface MfaAttemptService {
    void assertAllowed(Long userId, HttpServletRequest request);
    void recordFailure(Long userId, HttpServletRequest request);
    void recordSuccess(Long userId, HttpServletRequest request);
}
