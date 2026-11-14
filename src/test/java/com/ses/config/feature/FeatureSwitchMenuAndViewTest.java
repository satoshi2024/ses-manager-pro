package com.ses.config.feature;

import com.ses.common.constant.StatusConstants;
import com.ses.config.GlobalControllerAdvice;
import com.ses.dto.contract.RenewalCalendarItemDto;
import com.ses.dto.contract.RenewalCalendarResponseDto;
import com.ses.dto.servicedesk.CustomerHealthScoreDto;
import com.ses.entity.Menu;
import com.ses.mapper.ContractMapper;
import com.ses.service.MenuCacheService;
import com.ses.service.SystemConfigService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.impl.RenewalCalendarServiceImpl;
import com.ses.service.security.AuthorizationService;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import com.ses.service.servicedesk.CustomerHealthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.ses.config.feature.FeatureSwitchTestSupport.allOff;
import static com.ses.config.feature.FeatureSwitchTestSupport.allOn;
import static com.ses.config.feature.FeatureSwitchTestSupport.disabledOnly;
import static com.ses.config.feature.FeatureSwitchTestSupport.enabledOnly;
import static com.ses.config.feature.FeatureSwitchTestSupport.provider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 機能スイッチOFF時のサイドバー非表示・埋込み表示の抑止を検証する（DB不要）。
 */
class FeatureSwitchMenuAndViewTest {

    private static final List<String> ALL_MENU_KEYS = List.of("dashboard", "engineer", "lifecycle", "myLifecycle",
            "service-desk", "customer-success", "certification-learning-skill-gap", "myCertificationLearningGap",
            "management-report", "invoice");

    @AfterEach
    void clearTenant() {
        AccountingTenantContextHolder.clear();
    }

    @ParameterizedTest
    @EnumSource(Feature.class)
    void OFFの機能のメニューは管理者のサイドバーからも消え他の機能のメニューは残る(Feature disabled) {
        GlobalControllerAdvice advice = adminAdvice(disabledOnly(disabled));

        List<String> visible = advice.allowedMenus(admin());

        List<String> expected = new ArrayList<>(ALL_MENU_KEYS);
        expected.removeAll(disabled.menuKeys());
        assertThat(visible).containsExactlyElementsOf(expected);
    }

    @Test
    void 全機能OFFでもNF以外のメニューは変わらず全機能ONでは何も隠さない() {
        assertThat(adminAdvice(allOff()).allowedMenus(admin()))
                .containsExactly("dashboard", "engineer", "invoice");
        assertThat(adminAdvice(allOn()).allowedMenus(admin())).containsExactlyElementsOf(ALL_MENU_KEYS);
    }

    @Test
    void 権限で許可された一般ロールでもOFFの機能のメニューは消える() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MenuCacheService> menuProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AuthorizationService> authorizationProvider = mock(ObjectProvider.class);
        MenuCacheService menuCache = mock(MenuCacheService.class);
        AuthorizationService authorizationService = mock(AuthorizationService.class);
        when(menuProvider.getIfAvailable()).thenReturn(menuCache);
        when(authorizationProvider.getIfAvailable()).thenReturn(authorizationService);
        when(menuCache.getMenuKeysByRole("HR")).thenReturn(List.of("lifecycle", "invoice"));
        when(menuCache.getAllMenus()).thenReturn(List.of(menu("lifecycle", "/api/lifecycle"),
                menu("invoice", "/api/invoices")));
        UsernamePasswordAuthenticationToken hr = new UsernamePasswordAuthenticationToken(
                "hr", null, List.of(new SimpleGrantedAuthority("ROLE_HR")));
        when(authorizationService.isAllowed(hr, "lifecycle.view")).thenReturn(true);
        when(authorizationService.isAllowed(hr, "invoice.view")).thenReturn(true);
        GlobalControllerAdvice advice = new GlobalControllerAdvice(menuProvider, authorizationProvider);

        ReflectionTestUtils.setField(advice, "featureSwitchesProvider", provider(enabledOnly(Feature.NF02)));
        assertThat(advice.allowedMenus(hr)).containsExactly("invoice");
        ReflectionTestUtils.setField(advice, "featureSwitchesProvider", provider(enabledOnly(Feature.NF01)));
        assertThat(advice.allowedMenus(hr)).containsExactly("lifecycle", "invoice");
    }

    @Test
    void テンプレート用のOFF機能一覧を公開しFeatureSwitches未取得時は何も隠さない() {
        GlobalControllerAdvice advice = adminAdvice(disabledOnly(Feature.NF02, Feature.NF04));
        // 親（NF-02）がOFFなら子（顧客ポータルの問い合わせ）も実効値OFFとして一覧に載る。
        assertThat(advice.disabledFeatures()).containsExactly("nf02", "nf02-customer-portal", "nf04");
        // 子だけOFFなら親のメニュー・導線は残り、portalの問い合わせ導線と資格番号入力欄だけが隠れる。
        GlobalControllerAdvice childOff = adminAdvice(disabledOnly(Feature.NF02_CUSTOMER_PORTAL,
                Feature.NF03_CERTIFICATION_PII));
        assertThat(childOff.disabledFeatures()).containsExactly("nf02-customer-portal", "nf03-certification-pii");
        assertThat(childOff.allowedMenus(admin())).containsExactlyElementsOf(ALL_MENU_KEYS);

        GlobalControllerAdvice withoutSwitches = new GlobalControllerAdvice(null, null);
        assertThat(withoutSwitches.disabledFeatures()).isEmpty();
        ReflectionTestUtils.setField(withoutSwitches, "featureSwitchesProvider", provider(null));
        assertThat(withoutSwitches.disabledFeatures()).isEmpty();
        // FeatureSwitchesを取得できないスライスでは、既存のメニュー判定結果をそのまま返す。
        GlobalControllerAdvice sliceAdvice = adminAdvice(null);
        assertThat(sliceAdvice.allowedMenus(admin())).containsExactlyElementsOf(ALL_MENU_KEYS);
    }

    @Test
    void ログイン後の着地先はOFFの機能のメニューを選ばない() {
        MenuCacheService menuCache = mock(MenuCacheService.class);
        when(menuCache.getMenuKeysByRole("営業")).thenReturn(List.of("lifecycle", "project"));
        Menu lifecycle = menu("lifecycle", "/api/lifecycle");
        lifecycle.setPathPrefix("/lifecycle");
        Menu project = menu("project", "/api/projects");
        project.setPathPrefix("/project");
        when(menuCache.getAllMenus()).thenReturn(List.of(lifecycle, project));
        @SuppressWarnings("unchecked")
        ObjectProvider<com.ses.service.security.BreakGlassService> breakGlass = mock(ObjectProvider.class);
        com.ses.controller.page.LoginPageController controller = new com.ses.controller.page.LoginPageController(
                menuCache, new com.ses.config.OidcSecurityProperties(), breakGlass);
        UsernamePasswordAuthenticationToken sales = new UsernamePasswordAuthenticationToken(
                "sales", null, List.of(new SimpleGrantedAuthority("ROLE_営業")));

        ReflectionTestUtils.setField(controller, "featureSwitchesProvider", provider(disabledOnly(Feature.NF01)));
        assertThat(controller.index(sales)).isEqualTo("redirect:/project/list");
        ReflectionTestUtils.setField(controller, "featureSwitchesProvider", provider(allOn()));
        assertThat(controller.index(sales)).isEqualTo("redirect:/lifecycle");
    }

    @Test
    void NF02がOFFの間は契約更新カレンダーへ顧客ヘルスを埋め込まない() {
        CustomerHealthService healthService = mock(CustomerHealthService.class);
        RenewalCalendarServiceImpl service = renewalCalendar(healthService);
        ReflectionTestUtils.setField(service, "featureSwitches", disabledOnly(Feature.NF02));

        RenewalCalendarResponseDto response = service.getCalendar(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getHealthStatus()).isNull();
        verifyNoInteractions(healthService);
    }

    @Test
    void NF02がONなら契約更新カレンダーへ顧客ヘルスを従来どおり埋め込む() {
        CustomerHealthService healthService = mock(CustomerHealthService.class);
        CustomerHealthScoreDto health = new CustomerHealthScoreDto();
        health.setHealthStatus("CRITICAL");
        when(healthService.getHealthMapForCustomers(any())).thenReturn(Map.of(77L, health));
        RenewalCalendarServiceImpl service = renewalCalendar(healthService);
        ReflectionTestUtils.setField(service, "featureSwitches", enabledOnly(Feature.NF02));

        RenewalCalendarResponseDto response = service.getCalendar(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        assertThat(response.getItems().get(0).getHealthStatus()).isEqualTo("CRITICAL");
        verify(healthService).getHealthMapForCustomers(any());
    }

    private static RenewalCalendarServiceImpl renewalCalendar(CustomerHealthService healthService) {
        AccountingTenantContextHolder.setTenantId("tenant-a");
        ContractMapper contractMapper = mock(ContractMapper.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        DataScopeService dataScopeService = mock(DataScopeService.class);
        OrganizationScopeService organizationScopeService = mock(OrganizationScopeService.class);
        when(systemConfigService.getInt("notice.contract-end-days", 30)).thenReturn(30);
        when(dataScopeService.isScoped()).thenReturn(false);
        when(organizationScopeService.hasFullAccess()).thenReturn(true);
        RenewalCalendarItemDto item = new RenewalCalendarItemDto();
        item.setContractId(1L);
        item.setContractNo("C-1");
        item.setCustomerId(77L);
        item.setEndDate(LocalDate.of(2026, 7, 21));
        item.setStatus(StatusConstants.CONTRACT_ACTIVE);
        when(contractMapper.selectRenewalCalendarCandidates(any(), any(), any(), any(), any(), anyString()))
                .thenReturn(new ArrayList<>(List.of(item)));
        when(contractMapper.selectDraftStatusesByOriginalIds(any(), anyString())).thenReturn(List.of());
        RenewalCalendarServiceImpl service = new RenewalCalendarServiceImpl(contractMapper, systemConfigService,
                dataScopeService, organizationScopeService);
        ReflectionTestUtils.setField(service, "customerHealthService", healthService);
        return service;
    }

    private static GlobalControllerAdvice adminAdvice(FeatureSwitches switches) {
        @SuppressWarnings("unchecked")
        ObjectProvider<MenuCacheService> menuProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AuthorizationService> authorizationProvider = mock(ObjectProvider.class);
        MenuCacheService menuCache = mock(MenuCacheService.class);
        when(menuProvider.getIfAvailable()).thenReturn(menuCache);
        when(menuCache.getAllMenuKeys()).thenReturn(ALL_MENU_KEYS);
        GlobalControllerAdvice advice = new GlobalControllerAdvice(menuProvider, authorizationProvider);
        ReflectionTestUtils.setField(advice, "featureSwitchesProvider", provider(switches));
        return advice;
    }

    private static UsernamePasswordAuthenticationToken admin() {
        return new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_管理者")));
    }

    private static Menu menu(String key, String apiPrefix) {
        Menu menu = new Menu();
        menu.setMenuKey(key);
        menu.setApiPrefix(apiPrefix);
        return menu;
    }
}
