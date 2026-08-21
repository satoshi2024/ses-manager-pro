package com.ses.service.invoice.provider.impl;

import com.ses.common.exception.BusinessException;
import com.ses.service.invoice.provider.DigitalInvoiceProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S16-P1-01 / S16-P1-03: mock の HMAC fail-closed と prod 装配ガード。
 */
class MockFastAccountingProviderHmacTest {

    @Test
    void verifyWebhookSignature_requiresConfiguredSecret_failClosed() {
        MockFastAccountingProviderImpl provider = new MockFastAccountingProviderImpl();
        ReflectionTestUtils.setField(provider, "webhookHmacSecret", "");
        assertFalse(provider.verifyWebhookSignature("{}", "valid-sig"));
        assertFalse(provider.verifyWebhookSignature("{}", "anything"));
    }

    @Test
    void verifyWebhookSignature_rejectsMagicValidSig_withoutMatchingHmac() {
        MockFastAccountingProviderImpl provider = new MockFastAccountingProviderImpl();
        ReflectionTestUtils.setField(provider, "webhookHmacSecret", "test-secret");
        assertFalse(provider.verifyWebhookSignature("{\"status\":\"DELIVERED\"}", "valid-sig"));
    }

    @Test
    void verifyWebhookSignature_acceptsMatchingHmacHex() {
        MockFastAccountingProviderImpl provider = new MockFastAccountingProviderImpl();
        ReflectionTestUtils.setField(provider, "webhookHmacSecret", "test-secret");
        String body = "{\"status\":\"DELIVERED\"}";
        String sig = MockFastAccountingProviderImpl.hmacSha256Hex("test-secret", body);
        assertTrue(provider.verifyWebhookSignature(body, sig));
    }

    @Test
    void sendInvoice_withoutSecret_failClosed() {
        MockFastAccountingProviderImpl provider = new MockFastAccountingProviderImpl();
        ReflectionTestUtils.setField(provider, "webhookHmacSecret", " ");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> provider.sendInvoice("<xml/>", "1.1.3", "MSG-1"));
        assertEquals(503, ex.getCode());
    }

    @Test
    void mockIsGatedOutOfProd_andFailClosedIsProdOnly() {
        Profile mockProfile = MockFastAccountingProviderImpl.class.getAnnotation(Profile.class);
        assertNotNull(mockProfile);
        assertTrue(Arrays.asList(mockProfile.value()).contains("!prod"));

        ConditionalOnProperty mockProp = MockFastAccountingProviderImpl.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(mockProp);
        assertEquals("app.digital-invoice.provider", mockProp.name()[0]);
        assertEquals("mock", mockProp.havingValue());

        Profile failClosedProfile = FailClosedDigitalInvoiceProvider.class.getAnnotation(Profile.class);
        assertNotNull(failClosedProfile);
        assertTrue(Arrays.asList(failClosedProfile.value()).contains("prod"));
        ConditionalOnProperty failClosedProp =
                FailClosedDigitalInvoiceProvider.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(failClosedProp);
        assertEquals("app.digital-invoice.provider", failClosedProp.name()[0]);
        assertEquals("none", failClosedProp.havingValue());
    }

    @Test
    void failClosedProvider_rejectsSendAndWebhook() {
        FailClosedDigitalInvoiceProvider provider = new FailClosedDigitalInvoiceProvider();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> provider.sendInvoice("<xml/>", "1.1.3", "MSG-1"));
        assertEquals(503, ex.getCode());
        assertFalse(provider.verifyWebhookSignature("{}", "valid-sig"));
        assertFalse(provider.verifyWebhookSignature("{}", "any"));
    }
}

/**
 * test プロファイルでは mock bean が解決されること（prod 未使用の確認はアノテーションテスト側）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DigitalInvoiceProviderTestProfileWiringTest {

    @Autowired
    private DigitalInvoiceProvider provider;

    @Test
    void testProfileUsesMockProvider() {
        assertTrue(provider instanceof MockFastAccountingProviderImpl);
    }
}
