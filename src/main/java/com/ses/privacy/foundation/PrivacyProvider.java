package com.ses.privacy.foundation;

/** 将来adapterの境界。このincrementには実provider実装を含めない。 */
public interface PrivacyProvider {
    String providerId();
    String audience();
}
