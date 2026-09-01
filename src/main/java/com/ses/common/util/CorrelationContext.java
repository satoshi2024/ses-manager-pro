package com.ses.common.util;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * API、非同期ジョブ、外部プロバイダ呼出しを横断する相関コンテキスト。
 * 利用者入力は識別子として許可した文字だけを受け付ける。
 */
public final class CorrelationContext {

    public static final String CORRELATION_ID = "correlationId";
    public static final String INVOICE_ID = "invoiceId";
    public static final String DIGITAL_INVOICE_ID = "digitalInvoiceId";
    public static final String JOB_ID = "jobId";
    public static final String PROVIDER_OPERATION_ID = "providerOperationId";
    public static final String ERROR_CODE = "errorCode";
    public static final String ERROR_CATEGORY = "errorCategory";

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,100}");
    private static final int MAX_IDENTIFIER_LENGTH = 100;

    private CorrelationContext() {
    }

    public static String set(String requestedId) {
        return begin(requestedId);
    }

    public static String begin(String requestedId) {
        String id = safeIdentifier(requestedId);
        if (id == null) {
            id = "corr-" + UUID.randomUUID();
        }
        MDC.put(CORRELATION_ID, id);
        return id;
    }

    public static String ensure() {
        String current = current();
        return current != null ? current : begin(null);
    }

    public static String beginJob(Long jobId, String requestedId) {
        String id = begin(requestedId);
        put(JOB_ID, jobId);
        return id;
    }

    public static String current() {
        return MDC.get(CORRELATION_ID);
    }

    public static void put(String key, Object value) {
        if (key == null || value == null) {
            return;
        }
        String safe = safeIdentifier(String.valueOf(value));
        if (safe != null) {
            MDC.put(key, safe);
        }
    }

    public static String get(String key) {
        return key == null ? null : MDC.get(key);
    }

    public static String safeIdentifier(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() > MAX_IDENTIFIER_LENGTH) {
            return null;
        }
        String trimmed = value.trim();
        return SAFE_ID.matcher(trimmed).matches() ? trimmed : null;
    }

    public static void clear() {
        MDC.remove(CORRELATION_ID);
        MDC.remove(INVOICE_ID);
        MDC.remove(DIGITAL_INVOICE_ID);
        MDC.remove(JOB_ID);
        MDC.remove(PROVIDER_OPERATION_ID);
        MDC.remove(ERROR_CODE);
        MDC.remove(ERROR_CATEGORY);
    }
}
