package com.ses.privacy.production;

import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitchFilter;
import com.ses.config.feature.FeatureSwitchProperties;
import com.ses.config.feature.FeatureSwitches;
import com.ses.service.security.AuthorizationService;
import com.ses.test.DisableDefaultTenantTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NF07 の service 層の再認可（公開スイッチ → action 権限の順。§3.1.2 の6、§6.8）。
 *
 * <p>guard が現在の認証をそのまま判定へ渡すことを同一instanceで検証するため、principal を差し替える
 * 既定tenant拡張を無効にする（DBもtenant contextも使わない単体テスト）。</p>
 */
@DisableDefaultTenantTestContext
class PrivacyAccessGuardTest {

    private AuthorizationService authorizationService;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        authorizationService = mock(AuthorizationService.class);
        authentication = new UsernamePasswordAuthenticationToken("privacy-admin-user", null,
                List.of(new SimpleGrantedAuthority("ROLE_管理者")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 保持保全のスイッチがOFFなら権限を確認せず404にする() {
        PrivacyAccessGuard guard = guard(switches(false), new PrivacyFeatureProperties(), authorizationService);

        assertThrows(PrivacyAccessGuard.PrivacyEntryNotFoundException.class,
                () -> guard.requireAllowed(PrivacyAccessGuard.Capability.RETENTION, "privacy.policy.view"));
        verify(authorizationService, never()).isAllowed(any(), any());
    }

    @Test
    void 機能スイッチを取得できない場合もOFFとして404にする() {
        PrivacyAccessGuard guard = guard(null, new PrivacyFeatureProperties(), authorizationService);

        assertFalse(guard.isEnabled(PrivacyAccessGuard.Capability.RETENTION));
        assertFalse(guard.isEnabled(null));
        assertThrows(PrivacyAccessGuard.PrivacyEntryNotFoundException.class,
                () -> guard.requireAllowed(PrivacyAccessGuard.Capability.RETENTION, "privacy.view"));
    }

    @Test
    void スイッチONでprivacy_actionが許可されていれば通す() {
        when(authorizationService.isAllowed(authentication, "privacy.policy.view")).thenReturn(true);
        PrivacyAccessGuard guard = guard(switches(true), new PrivacyFeatureProperties(), authorizationService);

        assertDoesNotThrow(() -> guard.requireAllowed(PrivacyAccessGuard.Capability.RETENTION, "privacy.policy.view"));
        verify(authorizationService).isAllowed(authentication, "privacy.policy.view");
    }

    @Test
    void スイッチONでもaction権限が無い_判定部品が無い_例外_未認証なら403にする() {
        when(authorizationService.isAllowed(any(), eq("privacy.policy.view"))).thenReturn(false);
        assertForbidden(guard(switches(true), new PrivacyFeatureProperties(), authorizationService),
                "privacy.policy.view");

        assertForbidden(guard(switches(true), new PrivacyFeatureProperties(), null), "privacy.policy.view");

        AuthorizationService failing = mock(AuthorizationService.class);
        when(failing.isAllowed(any(), any())).thenThrow(new IllegalStateException("db unavailable"));
        assertForbidden(guard(switches(true), new PrivacyFeatureProperties(), failing), "privacy.policy.view");

        SecurityContextHolder.clearContext();
        AuthorizationService allowAll = mock(AuthorizationService.class);
        when(allowAll.isAllowed(any(), any())).thenReturn(true);
        assertForbidden(guard(switches(true), new PrivacyFeatureProperties(), allowAll), "privacy.policy.view");
    }

    @Test
    void privacy接頭辞でないactionは権限を確認せず403にする() {
        when(authorizationService.isAllowed(any(), any())).thenReturn(true);
        PrivacyAccessGuard guard = guard(switches(true), new PrivacyFeatureProperties(), authorizationService);

        assertForbidden(guard, "dashboard.view");
        assertForbidden(guard, "privacyx.view");
        assertForbidden(guard, null);
        verify(authorizationService, never()).isAllowed(any(), any());
    }

    @Test
    void DSARは機能スイッチ未登録のためprivacy_dsar_enabledを直接見る() {
        when(authorizationService.isAllowed(any(), eq("privacy.dsar.view"))).thenReturn(true);
        PrivacyFeatureProperties properties = new PrivacyFeatureProperties();
        PrivacyAccessGuard guard = guard(switches(true), properties, authorizationService);

        assertThrows(PrivacyAccessGuard.PrivacyEntryNotFoundException.class,
                () -> guard.requireAllowed(PrivacyAccessGuard.Capability.DSAR, "privacy.dsar.view"));
        properties.getDsar().setEnabled(true);
        assertTrue(guard.isEnabled(PrivacyAccessGuard.Capability.DSAR));
        assertDoesNotThrow(() -> guard.requireAllowed(PrivacyAccessGuard.Capability.DSAR, "privacy.dsar.view"));
        assertFalse(guard(switches(true), null, authorizationService).isEnabled(PrivacyAccessGuard.Capability.DSAR));
    }

    @Test
    void OFFの応答はFeatureSwitchFilterと同じstatusと本文である() {
        ResponseEntity<ApiResult<Void>> response = PrivacyAccessGuard.notFoundResponse();

        assertEquals(404, response.getStatusCode().value());
        assertEquals(404, response.getBody().getCode());
        assertEquals(FeatureSwitchFilter.NOT_FOUND_MESSAGE, response.getBody().getMessage());
        assertEquals(null, response.getBody().getData());
    }

    @Test
    void 機能スイッチの設定値はprivacy_retention_enabledの値そのものである() {
        PrivacyFeatureProperties privacy = new PrivacyFeatureProperties();
        FeatureSwitchProperties properties = new FeatureSwitchProperties();
        properties.usePrivacyFeatureProperties(privacy);
        FeatureSwitches switches = new FeatureSwitches(properties);

        assertFalse(switches.isEnabled(Feature.NF07_RETENTION), "既定はOFF");
        privacy.getRetention().setEnabled(true);
        assertTrue(switches.isEnabled(Feature.NF07_RETENTION));
        assertTrue(properties.toggle(Feature.NF07_RETENTION) == privacy.getRetention(), "別名を作らず同じ値を返す");
        privacy.setRetention(null);
        assertFalse(switches.isEnabled(Feature.NF07_RETENTION), "欠落はOFF");
        properties.usePrivacyFeatureProperties(null);
        assertFalse(switches.isEnabled(Feature.NF07_RETENTION), "注入が無ければ既定OFF");
    }

    private static void assertForbidden(PrivacyAccessGuard guard, String action) {
        BusinessException error = assertThrows(BusinessException.class,
                () -> guard.requireAllowed(PrivacyAccessGuard.Capability.RETENTION, action));
        assertEquals(403, error.getCode());
    }

    private static FeatureSwitches switches(boolean retentionEnabled) {
        FeatureSwitchProperties properties = new FeatureSwitchProperties();
        properties.toggle(Feature.NF07_RETENTION).setEnabled(retentionEnabled);
        return new FeatureSwitches(properties);
    }

    @SuppressWarnings("unchecked")
    private static PrivacyAccessGuard guard(FeatureSwitches switches, PrivacyFeatureProperties properties,
                                            AuthorizationService authorizationService) {
        ObjectProvider<FeatureSwitches> switchesProvider = mock(ObjectProvider.class);
        when(switchesProvider.getIfAvailable()).thenReturn(switches);
        ObjectProvider<PrivacyFeatureProperties> propertiesProvider = mock(ObjectProvider.class);
        when(propertiesProvider.getIfAvailable()).thenReturn(properties);
        ObjectProvider<AuthorizationService> authorizationProvider = mock(ObjectProvider.class);
        when(authorizationProvider.getIfAvailable()).thenReturn(authorizationService);
        return new PrivacyAccessGuard(switchesProvider, propertiesProvider, authorizationProvider);
    }
}
