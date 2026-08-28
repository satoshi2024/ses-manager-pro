package com.ses.service.certification;

import com.ses.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.lenient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CertificationNumberCryptoServiceTest {

    @Mock
    private CertificationNumberKeyProvider keyProvider;

    private CertificationNumberCryptoServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CertificationNumberCryptoServiceImpl(keyProvider);
        lenient().when(keyProvider.getCurrentKeyVersion()).thenReturn("v1");
        lenient().when(keyProvider.getKey("v1")).thenReturn(
                "certification-test-key-32bytes!!".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void encryptDecrypt_roundTrip() {
        CertificationNumberCryptoService.EncryptedCertificationNumber enc =
                service.encrypt("default", 42L, "ABC-12345-XYZ");
        assertNotNull(enc);
        assertEquals("CNF1", enc.cipherFormat());
        assertEquals("v1", enc.keyVersion());
        assertNotNull(enc.masked());
        assertNotEquals("ABC-12345-XYZ", enc.masked());

        String plain = service.decrypt("default", 42L, enc.encrypted(), enc.keyVersion(), enc.cipherFormat());
        assertEquals("ABC-12345-XYZ", plain);
    }

    @Test
    void decrypt_wrongRecordId_failsClosed() {
        CertificationNumberCryptoService.EncryptedCertificationNumber enc =
                service.encrypt("default", 42L, "SECRET");
        assertThrows(BusinessException.class,
                () -> service.decrypt("default", 99L, enc.encrypted(), enc.keyVersion(), enc.cipherFormat()));
    }

    @Test
    void decrypt_unknownKeyVersion_failsClosed() {
        CertificationNumberCryptoService.EncryptedCertificationNumber enc =
                service.encrypt("default", 1L, "SECRET");
        when(keyProvider.getKey("missing")).thenThrow(new IllegalArgumentException("unknown"));
        assertThrows(BusinessException.class,
                () -> service.decrypt("default", 1L, enc.encrypted(), "missing", enc.cipherFormat()));
    }

    @Test
    void encrypt_blank_returnsNull() {
        assertNull(service.encrypt("default", 1L, "  "));
    }
}
