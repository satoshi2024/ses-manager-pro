package com.ses.service.integration;

/** 外部メールプロバイダの境界。CRM/請求側はSMTP SDKへ直接依存しない。 */
public interface EmailProviderAdapter {
    void send(String recipient, String subject, String body);
}
