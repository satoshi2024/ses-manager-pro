package com.ses.service.impl;

import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import com.ses.featureenablement.FeatureEnablementH2Fixture;
import com.ses.featureenablement.FeatureEnablementTestConfiguration;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.service.FeatureEnablementActor;
import com.ses.mapper.SysUserMapper;
import com.ses.service.SysUserService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FE-A05（design §11.1 の review P3-11）: 利用者が管理者の role を得た時（作成・role の変更）に、有効な管理者全員へ通知し、送付の記録を
 * 監査 {@code FEATURE_ADMIN_ROLE_GRANTED_NOTICE} に残す（利用者の ID だけ）。管理者以外の role の付与では通知しない。
 */
@SpringBootTest(properties = "app.feature-enablement.source=database")
@ActiveProfiles("test")
@Import(FeatureEnablementTestConfiguration.class)
@Transactional
@Sql(scripts = "/sql/permission-group-seed-h2.sql")
class SysUserServiceImplTest {

    @Autowired
    private SysUserService sysUserService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FeatureEnablementCatalog catalog;
    @Autowired
    private SysUserMapper sysUserMapper;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
    }

    @Test
    void 管理者のroleの付与で全ての管理者へ通知し送付を監査に残す() {
        FeatureEnablementH2Fixture fixture = new FeatureEnablementH2Fixture(jdbc, catalog);
        FeatureEnablementActor operator = fixture.admin();
        FeatureEnablementActor other = fixture.admin();
        long before = fixture.countAudit("FEATURE_ADMIN_ROLE_GRANTED_NOTICE");
        Authentication authentication = authentication(operator);
        AccountingTenantContextHolder.setTenantId(FeatureEnablementH2Fixture.TENANT);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        SysUser created = new SysUser();
        created.setUsername("fegrant" + UUID.randomUUID().toString().substring(0, 8));
        created.setPassword("Grant-Password-2026!");
        created.setRealName("付与試験");
        created.setRole("管理者");
        sysUserService.createUser(created, authentication);

        assertThat(fixture.countAudit("FEATURE_ADMIN_ROLE_GRANTED_NOTICE")).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT after_state FROM t_audit_log WHERE application_code = "
                + "'FEATURE_ADMIN_ROLE_GRANTED_NOTICE' ORDER BY id DESC LIMIT 1", String.class))
                .contains("\"grantedUserId\":" + created.getId()).doesNotContain("付与試験");
        FeatureEnablementH2Fixture.runAfterCommit();
        assertThat(FeatureEnablementH2Fixture.countNotices(other.userId(), "adminGranted")).as("他の有効な管理者へ").isEqualTo(1);
        assertThat(FeatureEnablementH2Fixture.countNotices(operator.userId(), "adminGranted")).as("付与した管理者へも")
                .isEqualTo(1);
        assertThat(FeatureEnablementH2Fixture.countNotices(created.getId(), "adminGranted")).as("同じ transaction で管理者になった利用者へも")
                .isEqualTo(1);

        // role を変えない更新では通知しない。role の変更で管理者になれば通知する
        FeatureEnablementActor sales = fixture.user("営業");
        SysUser rename = new SysUser();
        rename.setRealName("営業試験");
        sysUserService.updateUser(sales.userId(), rename, authentication);
        assertThat(fixture.countAudit("FEATURE_ADMIN_ROLE_GRANTED_NOTICE")).isEqualTo(before + 1);

        SysUser promote = new SysUser();
        promote.setRole("管理者");
        sysUserService.updateUser(sales.userId(), promote, authentication);
        assertThat(fixture.countAudit("FEATURE_ADMIN_ROLE_GRANTED_NOTICE")).isEqualTo(before + 2);

        // 無効にした管理者を有効に戻すことも管理者の能力を得ることとして通知する（review rb-fe-a1 P3-8）。無効化では通知しない
        sysUserService.updateUserStatus(sales.userId(), 0, authentication);
        assertThat(fixture.countAudit("FEATURE_ADMIN_ROLE_GRANTED_NOTICE")).isEqualTo(before + 2);
        sysUserService.updateUserStatus(sales.userId(), 1, authentication);
        assertThat(fixture.countAudit("FEATURE_ADMIN_ROLE_GRANTED_NOTICE")).isEqualTo(before + 3);
    }

    private Authentication authentication(FeatureEnablementActor actor) {
        SysUser operator = sysUserMapper.selectById(actor.userId());
        LoginUser principal = new LoginUser(operator, List.of(new SimpleGrantedAuthority("ROLE_管理者")),
                FeatureEnablementH2Fixture.TENANT);
        return new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities());
    }
}
