package com.ses.service.ai.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiResponseLogMetaTest {

    @Test
    void responseSha256は本文を返さず不可逆である() {
        String secret = "{\"error\":\"AIzaSySecretKey user@example.com\"}";
        String hash = GeminiTextServiceImpl.responseSha256(secret);
        assertThat(hash).hasSize(64);
        assertThat(hash).doesNotContain("AIza");
        assertThat(hash).doesNotContain("user@");
        assertThat(hash).doesNotContain(secret);
        assertThat(GeminiTextServiceImpl.responseSha256(secret)).isEqualTo(hash);
        assertThat(GeminiTextServiceImpl.responseSha256(secret + "x")).isNotEqualTo(hash);
    }

    @Test
    void responseByteLengthは長さのみ() {
        assertThat(GeminiTextServiceImpl.responseByteLength(null)).isZero();
        assertThat(GeminiTextServiceImpl.responseByteLength("abc")).isEqualTo(3);
    }
}
