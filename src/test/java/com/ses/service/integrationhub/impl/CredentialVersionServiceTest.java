package com.ses.service.integrationhub.impl;

import com.ses.entity.integrationhub.CredentialVersion;
import com.ses.mapper.CredentialVersionMapper;
import com.ses.service.integrationhub.crypto.IntegrationHubSecretCryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        service = new CredentialVersionServiceImpl(mapper, crypto);
        when(crypto.encrypt("client-a", 2, "credential", NEW_SECRET)).thenReturn("IHG1:v1:iv:cipher");
        when(crypto.credentialHash("client-a", 2, NEW_SECRET))
                .thenReturn("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    /** 管理APIが発行する形（32 byte乱数のbase64url、43文字）の合成secret。 */
    private static final String NEW_SECRET = "synthetic_rotation_secret_v2_base64url_43ch";

    /** N5-P3-2: 保存するhashはpepper付きのcredentialHashで、ソルトの無いSHA-256を使わない。短いsecretは発行しない。 */
    @Test
    void issueはpepper付きhashを保存し短いsecretや形式外のsecretを拒否する() {
        when(mapper.selectRotatable(20L)).thenReturn(List.of());
        when(mapper.insert(any(CredentialVersion.class))).thenReturn(1);

        CredentialVersion created = service.issue(20L, "client-a", 2, "key-2", NEW_SECRET, now);

        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", created.getSecretHash());
        verify(crypto).credentialHash("client-a", 2, NEW_SECRET);
        for (String weak : List.of("new-secret", NEW_SECRET.substring(1), NEW_SECRET + "+", NEW_SECRET + "=",
                " " + NEW_SECRET)) {
            IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class, () -> service.issue(20L, "client-a", 3, "key-3", weak, now));
            org.junit.jupiter.api.Assertions.assertFalse(failure.getMessage().contains(weak.trim()),
                    "例外文言へsecretを出さない");
        }
        org.mockito.Mockito.verify(crypto, org.mockito.Mockito.never()).encrypt(any(),
                org.mockito.ArgumentMatchers.eq(3), any(), any());
    }

    @Test
    void issueは旧ACTIVEを24時間OVERLAPへ移し新secretを90日credentialとして保存する() {
        CredentialVersion old = CredentialVersion.builder().id(10L).credentialVersion(1).status("ACTIVE")
                .version(3).build();
        when(mapper.selectRotatable(20L)).thenReturn(List.of(old));
        when(mapper.updateById(old)).thenReturn(1);
        when(mapper.insert(any(CredentialVersion.class))).thenAnswer(invocation -> {
            CredentialVersion created = invocation.getArgument(0);
            created.setId(11L);
            return 1;
        });

        CredentialVersion created = service.issue(20L, "client-a", 2, "key-2", NEW_SECRET, now);

        assertEquals("OVERLAP", old.getStatus());
        assertEquals(now.plusHours(24), old.getOverlapUntil());
        assertEquals("IHG1:v1:iv:cipher", created.getEncryptedSecret());
        assertEquals(now.plusDays(90), created.getExpiresAt());
        assertNotEquals(NEW_SECRET, created.getEncryptedSecret());
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
