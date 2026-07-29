package com.ses.config;

import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * dashboardScopeKeyGenerator が組織スコープを含めてキーを分離することを検証する。
 * P0-1 回帰テスト: マネージャーと管理者で同じキーにならないことを保証する。
 */
class DashboardScopeKeyGeneratorTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void orgScopedManager_getDifferentKeyFromAdmin() throws Exception {
        // 組織スコープ有効・DataScope無効の状態を再現
        DataScopeService dataScope = mock(DataScopeService.class);
        when(dataScope.isScoped()).thenReturn(false);

        OrganizationScopeService orgScopeManager = mock(OrganizationScopeService.class);
        when(orgScopeManager.hasFullAccess()).thenReturn(false); // マネージャー

        OrganizationScopeService orgScopeAdmin = mock(OrganizationScopeService.class);
        when(orgScopeAdmin.hasFullAccess()).thenReturn(true); // 管理者

        // マネージャーのキーを生成
        setAuth(10L, "マネージャー");
        KeyGenerator managerKeyGen = buildKeyGenerator(dataScope, orgScopeManager);
        Method method = DashboardScopeKeyGeneratorTest.class.getDeclaredMethod("dummyMethod");
        Object managerKey = managerKeyGen.generate(this, method, new Object[]{null});

        // 管理者のキーを生成
        setAuth(1L, "管理者");
        KeyGenerator adminKeyGen = buildKeyGenerator(dataScope, orgScopeAdmin);
        Object adminKey = adminKeyGen.generate(this, method, new Object[]{null});

        assertThat(managerKey).isNotEqualTo(adminKey);
        // マネージャーはユーザーID入り、管理者はALL
        assertThat(managerKey.toString()).contains("U10");
        assertThat(adminKey.toString()).contains("ALL");
    }

    @Test
    void dataScopeActive_stillKeysPerUser() throws Exception {
        DataScopeService dataScope = mock(DataScopeService.class);
        when(dataScope.isScoped()).thenReturn(true);

        OrganizationScopeService orgScope = mock(OrganizationScopeService.class);
        when(orgScope.hasFullAccess()).thenReturn(true); // DataScopeだけで分離

        setAuth(5L, "営業");
        KeyGenerator keyGen = buildKeyGenerator(dataScope, orgScope);
        Method method = DashboardScopeKeyGeneratorTest.class.getDeclaredMethod("dummyMethod");
        Object key = keyGen.generate(this, method, new Object[]{null});

        assertThat(key.toString()).contains("U5");
    }

    @Test
    void twoOrgScopedManagers_neverShareDashboardKey() throws Exception {
        DataScopeService dataScope = mock(DataScopeService.class);
        when(dataScope.isScoped()).thenReturn(false);
        OrganizationScopeService orgScope = mock(OrganizationScopeService.class);
        when(orgScope.hasFullAccess()).thenReturn(false);
        KeyGenerator keyGen = buildKeyGenerator(dataScope, orgScope);
        Method method = DashboardScopeKeyGeneratorTest.class.getDeclaredMethod("dummyMethod");

        setAuth(20L, "マネージャー");
        Object managerA = keyGen.generate(this, method, new Object[]{2026});
        setAuth(21L, "マネージャー");
        Object managerB = keyGen.generate(this, method, new Object[]{2026});

        assertThat(managerA).isNotEqualTo(managerB);
        assertThat(managerA.toString()).contains("U20");
        assertThat(managerB.toString()).contains("U21");
    }

    @Test
    void bothScopesInactive_allShareKey() throws Exception {
        DataScopeService dataScope = mock(DataScopeService.class);
        when(dataScope.isScoped()).thenReturn(false);

        OrganizationScopeService orgScope = mock(OrganizationScopeService.class);
        when(orgScope.hasFullAccess()).thenReturn(true);

        setAuth(1L, "管理者");
        KeyGenerator keyGen = buildKeyGenerator(dataScope, orgScope);
        Method method = DashboardScopeKeyGeneratorTest.class.getDeclaredMethod("dummyMethod");
        Object key = keyGen.generate(this, method, new Object[]{2024});

        assertThat(key.toString()).contains("ALL");
    }

    @Test
    void noScopeServiceAvailable_fallsBackToAll() throws Exception {
        // サービス未配線時は安全にALL（テストスライス等）
        KeyGenerator keyGen = buildKeyGenerator(null, null);
        setAuth(1L, "管理者");
        Method method = DashboardScopeKeyGeneratorTest.class.getDeclaredMethod("dummyMethod");
        Object key = keyGen.generate(this, method, new Object[]{null});

        assertThat(key.toString()).contains("ALL");
    }

    @SuppressWarnings("unused")
    private void dummyMethod() {}

    /**
     * 同一ユーザーでも、所属・組織階層・DataScopeが変わればキーが変わること。
     *
     * <p>ユーザーIDだけでキーを分けると、異動や権限剥奪の直後にTTL(既定60秒)が切れるまで
     * 旧scopeで計算した他組織のデータを返し続ける（第十三次Review P1-6の短時間越権）。
     */
    @Test
    void sameUser_keyChangesWhenScopeVersionBumps() throws Exception {
        DataScopeService dataScope = mock(DataScopeService.class);
        when(dataScope.isScoped()).thenReturn(false);
        OrganizationScopeService orgScope = mock(OrganizationScopeService.class);
        when(orgScope.hasFullAccess()).thenReturn(false);

        com.ses.service.security.ScopeVersionRegistry scopeVersion =
                new com.ses.service.security.ScopeVersionRegistry();
        setAuth(10L, "マネージャー");
        Method method = DashboardScopeKeyGeneratorTest.class.getDeclaredMethod("dummyMethod");

        KeyGenerator keyGen = buildKeyGenerator(dataScope, orgScope, scopeVersion);
        Object before = keyGen.generate(this, method, new Object[]{null});
        // 同じ状態なら同じキー（キャッシュが効く）。
        assertThat(keyGen.generate(this, method, new Object[]{null})).isEqualTo(before);

        // 組織所属が変わった＝可視範囲が変わった。
        scopeVersion.bump();
        Object after = keyGen.generate(this, method, new Object[]{null});

        assertThat(after).isNotEqualTo(before);
        assertThat(after.toString()).contains("U10");
    }

    /** 管理者など共有キー("ALL")の利用者も、世代が進めば作り直される。 */
    @Test
    void sharedAllKey_alsoChangesWhenScopeVersionBumps() throws Exception {
        DataScopeService dataScope = mock(DataScopeService.class);
        when(dataScope.isScoped()).thenReturn(false);
        OrganizationScopeService orgScope = mock(OrganizationScopeService.class);
        when(orgScope.hasFullAccess()).thenReturn(true);

        com.ses.service.security.ScopeVersionRegistry scopeVersion =
                new com.ses.service.security.ScopeVersionRegistry();
        setAuth(1L, "管理者");
        Method method = DashboardScopeKeyGeneratorTest.class.getDeclaredMethod("dummyMethod");
        KeyGenerator keyGen = buildKeyGenerator(dataScope, orgScope, scopeVersion);

        Object before = keyGen.generate(this, method, new Object[]{null});
        scopeVersion.bump();
        Object after = keyGen.generate(this, method, new Object[]{null});

        assertThat(after).isNotEqualTo(before);
        assertThat(after.toString()).contains("ALL");
    }

    private void setAuth(Long userId, String role) {
        // SecurityUtils.currentUserId() は principal が String の場合 parseLong する
        var auth = new UsernamePasswordAuthenticationToken(
                String.valueOf(userId), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private KeyGenerator buildKeyGenerator(DataScopeService dataScope,
                                            OrganizationScopeService orgScope) {
        return buildKeyGenerator(dataScope, orgScope,
                new com.ses.service.security.ScopeVersionRegistry());
    }

    @SuppressWarnings("unchecked")
    private KeyGenerator buildKeyGenerator(DataScopeService dataScope,
                                            OrganizationScopeService orgScope,
                                            com.ses.service.security.ScopeVersionRegistry scopeVersion) {
        ObjectProvider<DataScopeService> dsProvider = mock(ObjectProvider.class);
        when(dsProvider.getIfAvailable()).thenReturn(dataScope);
        ObjectProvider<OrganizationScopeService> orgProvider = mock(ObjectProvider.class);
        when(orgProvider.getIfAvailable()).thenReturn(orgScope);
        ObjectProvider<com.ses.service.security.ScopeVersionRegistry> versionProvider = mock(ObjectProvider.class);
        when(versionProvider.getIfAvailable()).thenReturn(scopeVersion);

        CacheConfig config = new CacheConfig();
        return config.dashboardScopeKeyGenerator(dsProvider, orgProvider, versionProvider);
    }
}
