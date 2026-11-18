package com.ses.privacy.production.retention.catalog;

import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import com.ses.service.security.ActionPermissionResolver;
import com.ses.service.security.AuthorizationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/privacy/catalog} の action（{@code privacy.catalog.view}）は、W3-14（V191）の既存の規則だけで
 * privacy-admin に限られる（新しい seed・allow-list を足さない。baseline + deny）。
 * fixture の利用者は {@code ActionPermissionMatrixTest} と同じ {@code permission-group-seed-h2.sql}。
 */
@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "/sql/permission-group-seed-h2.sql")
@Transactional
class PrivacyCatalogPermissionTest {

    @Autowired
    private AuthorizationService authorizationService;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void catalogの照会actionは既存のprivacy_denyでprivacy_admin所属の管理者だけに許可される() {
        String action = ActionPermissionResolver.resolve("GET", "/api/privacy/catalog");
        assertThat(action).isEqualTo(PrivacyCatalogService.VIEW_ACTION);

        assertThat(authorizationService.isAllowed(authenticate(9005L, "管理者"), action)).as("privacy-admin").isTrue();
        for (Object[] denied : List.of(new Object[]{9007L, "管理者"}, new Object[]{9008L, "管理者"},
                new Object[]{9000L, "管理者"}, new Object[]{9001L, "営業"}, new Object[]{9002L, "HR"},
                new Object[]{9003L, "マネージャー"}, new Object[]{9004L, "要員"}, new Object[]{9006L, "営業"})) {
            assertThat(authorizationService.isAllowed(authenticate((Long) denied[0], (String) denied[1]), action))
                    .as(denied[0] + " " + denied[1]).isFalse();
        }
    }

    private Authentication authenticate(Long id, String role) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername("user-" + id);
        user.setRole(role);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                new LoginUser(user, List.of()), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return authentication;
    }
}
