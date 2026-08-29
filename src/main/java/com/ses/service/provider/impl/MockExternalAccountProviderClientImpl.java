package com.ses.service.provider.impl;

import com.ses.entity.ExternalAccountReference;
import com.ses.service.provider.ExternalAccountProviderClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 外部アカウントプロバイダ連携モック実装
 */
@Slf4j
@Service
public class MockExternalAccountProviderClientImpl implements ExternalAccountProviderClient {

    private final Map<Long, RevokeConfirmationStatus> mockStatusMap = new ConcurrentHashMap<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    private static String maskIdentifier(String identifier) {
        if (!StringUtils.hasText(identifier)) return "***";
        int at = identifier.indexOf('@');
        if (at > 2) return identifier.substring(0, 2) + "***" + identifier.substring(at);
        if (identifier.length() > 4) return identifier.substring(0, 2) + "***" + identifier.substring(identifier.length() - 2);
        return "***";
    }

    @Override
    public boolean requestRevoke(ExternalAccountReference accountRef) {
        if (accountRef == null) return false;
        requestCount.incrementAndGet();
        String maskedIdentifier = maskIdentifier(accountRef.getAccountIdentifier());
        log.info("Mock external revoke request sent: id={}, identifier={}",
                accountRef.getId(), maskedIdentifier);
        // 既にテスト用にモックステータスがセットされていなければデフォルトで CONFIRMED
        mockStatusMap.putIfAbsent(accountRef.getId(), RevokeConfirmationStatus.CONFIRMED);
        return true;
    }

    @Override
    public RevokeConfirmationStatus checkRevokeConfirmation(ExternalAccountReference accountRef) {
        if (accountRef == null) {
            return RevokeConfirmationStatus.FAILED_OR_TIMEOUT;
        }
        RevokeConfirmationStatus status = mockStatusMap.getOrDefault(accountRef.getId(), RevokeConfirmationStatus.CONFIRMED);
        log.info("Mock external revoke status checked: id={}, status={}", accountRef.getId(), status);
        return status;
    }

    /**
     * テスト用にステータスを差し替えるヘルパー
     */
    public void setMockStatus(Long accountRefId, RevokeConfirmationStatus status) {
        mockStatusMap.put(accountRefId, status);
    }

    public int getRequestCount() {
        return requestCount.get();
    }

    public void resetRequestCount() {
        requestCount.set(0);
    }
}
