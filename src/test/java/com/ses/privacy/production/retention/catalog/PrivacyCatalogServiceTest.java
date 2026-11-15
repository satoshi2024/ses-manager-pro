package com.ses.privacy.production.retention.catalog;

import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.config.OidcSecurityProperties;
import com.ses.entity.SysUser;
import com.ses.entity.privacy.PiiDataElement;
import com.ses.mapper.privacy.PiiDataElementMapper;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.PrivacyCoverageReport;
import com.ses.privacy.production.ProductionPrivacyProviderRegistry;
import com.ses.privacy.production.provider.EngineerProfileProvider;
import com.ses.privacy.production.provider.PrivacyReadOnlyQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** catalog API の service: 公開スイッチ・privacy.catalog.view の再判定、tenant 一致、照会失敗は503、書込みなし。 */
class PrivacyCatalogServiceTest {

    private PrivacyAccessGuard guard;
    private PiiDataElementMapper mapper;
    private PrivacyReadOnlyQuery query;
    private PrivacyCatalogService service;

    /** LambdaQueryWrapper の列指定は MyBatis-Plus の表情報を要する（Spring の test が先に動いたかどうかに依存させない）。 */
    @org.junit.jupiter.api.BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), PiiDataElement.class);
    }

    @BeforeEach
    void setUp() {
        guard = mock(PrivacyAccessGuard.class);
        mapper = mock(PiiDataElementMapper.class);
        query = mock(PrivacyReadOnlyQuery.class);
        service = new PrivacyCatalogService(guard, mapper,
                new ProductionPrivacyProviderRegistry(List.of(new EngineerProfileProvider(query))),
                new OidcSecurityProperties());
        authenticate("default");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 公開スイッチOFFと権限なしは何も読まずに例外をそのまま返す() {
        doThrow(new PrivacyAccessGuard.PrivacyEntryNotFoundException())
                .when(guard).requireAllowed(PrivacyAccessGuard.Capability.RETENTION, "privacy.catalog.view");
        assertThatThrownBy(service::catalog).isInstanceOf(PrivacyAccessGuard.PrivacyEntryNotFoundException.class);

        doThrow(BusinessException.of(403, "error.accessDenied"))
                .when(guard).requireAllowed(PrivacyAccessGuard.Capability.RETENTION, "privacy.catalog.view");
        assertThatThrownBy(service::catalog).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(403));
        verifyNoInteractions(mapper, query);
    }

    @Test
    void 照会者のtenantが設定tenantと一致しなければ403() {
        authenticate("tenant-x");

        assertThatThrownBy(service::catalog).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(403));
        verifyNoInteractions(mapper);
    }

    @Test
    void catalogを読んでproviderと突き合わせ未登録を理由付きで返しproviderの照会は呼ばない() {
        when(mapper.selectList(any())).thenReturn(List.of(
                row("ENGINEER_PROFILE", "engineer-profile", "ATTRIBUTABLE"),
                row("AUDIT_LOG", null, "UNKNOWN")));

        PrivacyCoverageReport report = service.catalog();

        verify(guard).requireAllowed(PrivacyAccessGuard.Capability.RETENTION, "privacy.catalog.view");
        assertThat(report.tenantId()).isEqualTo("default");
        assertThat(report.registeredElements()).extracting(PrivacyCoverageReport.ElementEntry::dataElementId)
                .containsExactly("ENGINEER_PROFILE");
        assertThat(report.unregisteredElements()).singleElement().satisfies(e -> {
            assertThat(e.dataElementId()).isEqualTo("AUDIT_LOG");
            assertThat(e.reason()).isEqualTo("PROVIDER_UNREGISTERED");
            assertThat(e.subjectAttribution()).isEqualTo("UNKNOWN");
        });
        assertThat(report.uncatalogedProviderElements()).extracting(PrivacyCoverageReport.MissingElement::dataElementId)
                .containsExactly("ENGINEER_PHOTO");
        verifyNoInteractions(query);
    }

    @Test
    void catalogを照会できなければ503() {
        when(mapper.selectList(any())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(service::catalog).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo(503));
    }

    private static PiiDataElement row(String id, String providerId, String attribution) {
        PiiDataElement row = new PiiDataElement();
        row.setTenantId("default");
        row.setDataElementId(id);
        row.setStorageKind("DB_COLUMN");
        row.setStorageRef("t.x");
        row.setProviderId(providerId);
        row.setSubjectAttribution(attribution);
        return row;
    }

    private static void authenticate(String tenantId) {
        SysUser user = new SysUser();
        user.setId(17L);
        user.setRole("管理者");
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_管理者"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(user, authorities, tenantId), null, authorities));
    }
}
