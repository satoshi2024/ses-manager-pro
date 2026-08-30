package com.ses.service.integrationhub.impl;

import com.ses.entity.integrationhub.CredentialVersion;
import com.ses.mapper.CredentialVersionMapper;
import com.ses.service.integrationhub.crypto.IntegrationHubSecretCryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** NF-05 F1: credential expiry、24時間overlap、revokeのCAS。 */
class CredentialVersionServiceTest {
    private CredentialVersionMapper mapper;
    private IntegrationHubSecretCryptoService crypto;
    private CredentialVersionServiceImpl service;
    private final LocalDateTime now = LocalDateTime.of(2026, 8, 30, 12, 0);

    @BeforeEach
    void setUp() {
        mapper = mock(CredentialVersionMapper.class);
        crypto = mock(IntegrationHubSecretCryptoService.class);
        service = new CredentialVersionServiceImpl(crypto);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(crypto.encrypt("client-a", 2, "credential", "new-secret")).thenReturn("IHG1:v1:iv:cipher");
        when(crypto.sha256Hex("new-secret")).thenReturn("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    @Test
    void issueは旧ACTIVEを24時間OVERLAPへ移し新secretを90日credentialとして保存する() {
        CredentialVersion old = CredentialVersion.builder().id(10L).credentialVersion(1).status("ACTIVE")
                .version(3).build();
        when(mapper.selectRotatable(20L)).thenReturn(List.of(old));
        when(mapper.insert(any(CredentialVersion.class))).thenAnswer(invocation -> {
            CredentialVersion created = invocation.getArgument(0);
            created.setId(11L);
            return 1;
        });

        CredentialVersion created = service.issue(20L, "client-a", 2, "key-2", "new-secret", now);

        assertEquals("OVERLAP", old.getStatus());
        assertEquals(now.plusHours(24), old.getOverlapUntil());
        assertEquals("IHG1:v1:iv:cipher", created.getEncryptedSecret());
        assertEquals(now.plusDays(90), created.getExpiresAt());
        assertNotEquals("new-secret", created.getEncryptedSecret());
        verify(mapper).updateById(old);
    }

    @Test
    void revokeはversion付きCASで即時REVOKEDになる() {
        CredentialVersion row = CredentialVersion.builder().id(10L).credentialVersion(1).version(3).status("ACTIVE").build();
        when(mapper.selectForUpdate(20L, 1)).thenReturn(row);
        when(mapper.revoke(20L, 1, 3, now)).thenReturn(1);

        assertTrue(service.revoke(20L, 1, now));
        verify(mapper).revoke(20L, 1, 3, now);
    }
}
