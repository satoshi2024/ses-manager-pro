package com.ses.privacy.foundation;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** 試験専用provider。生成fixture以外のsourceへ接続する機能は持たない。 */
final class SyntheticPrivacyProvider implements PrivacyProvider {
    private final Map<String, String> generatedValues;
    private final AtomicInteger calls = new AtomicInteger();

    SyntheticPrivacyProvider(Map<String, String> generatedValues) {
        this.generatedValues = Map.copyOf(generatedValues);
    }

    @Override
    public String providerId() {
        return SyntheticAuthorityFixture.PROVIDER;
    }

    @Override
    public String audience() {
        return SyntheticEnvironmentGuard.SYNTHETIC_AUDIENCE;
    }

    String readGeneratedFixture(String reference) {
        calls.incrementAndGet();
        String value = generatedValues.get(reference);
        if (value == null) throw new SecurityException("synthetic fixture referenceがありません");
        return value;
    }

    int invocationCount() {
        return calls.get();
    }
}
