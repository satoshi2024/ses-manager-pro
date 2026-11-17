package com.ses.portal;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityHashUtil;
import com.ses.dto.portal.PortalAcceptInvitationRequest;
import com.ses.entity.PortalInvitation;
import com.ses.entity.PortalOrganization;
import com.ses.entity.PortalUser;
import com.ses.mapper.PortalInvitationMapper;
import com.ses.service.portal.PortalAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * RB-PRERUN 3: 招待は指定email・組織・権限へ固定する（G3、field-inventory §1.1、requirements R1.2）。
 * ADMIN招待を受諾した利用者は組織管理者（org.admin）になり、MEMBER招待では付与しない。
 * 組織管理者が既にいる組織へのADMIN招待は、発行時（PortalAdminServiceImpl）に加えて受諾時にも拒否し、
 * 2人目の組織管理者を招待だけで作らない（2人目以降は組織管理者の承認が要る）。同時受諾の直列化は
 * {@code PortalAdminInvitationAcceptConcurrentMySqlTest} が実MySQLで確かめる。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@com.ses.test.DisableDefaultTenantTestContext
class PortalInvitationRoleAcceptanceTest extends PortalTestSupport {

    private static final String ORG_ADMIN = "org.admin";

    @Autowired
    private PortalAuthService portalAuthService;
    @Autowired
    private PortalInvitationMapper invitationMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    protected JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    @Test
    void ADMIN招待を受諾すると組織管理者の権限が付く() {
        PortalOrganization org = createCustomerOrg("role-admin-" + unique());
        String email = "role-admin-" + unique() + "@example.com";
        String token = invite(org, email, "ADMIN");

        portalAuthService.acceptInvitation(acceptRequest(token, email), new MockHttpServletRequest());

        PortalUser accepted = userMapper.selectByEmail(email);
        assertThat(accepted).isNotNull();
        assertThat(permissionKeys(accepted.getId()))
                .containsExactly(ORG_ADMIN, "service-desk.create", "service-desk.view");
        assertThat(orgAdminCount(org.getId())).isEqualTo(1);
    }

    @Test
    void MEMBER招待の受諾では組織管理者の権限を付けない() {
        PortalOrganization org = createCustomerOrg("role-member-" + unique());
        String email = "role-member-" + unique() + "@example.com";
        String token = invite(org, email, "MEMBER");

        portalAuthService.acceptInvitation(acceptRequest(token, email), new MockHttpServletRequest());

        PortalUser accepted = userMapper.selectByEmail(email);
        assertThat(accepted).isNotNull();
        assertThat(permissionKeys(accepted.getId())).containsExactly("service-desk.create", "service-desk.view");
        assertThat(orgAdminCount(org.getId())).isZero();
    }

    @Test
    void 組織管理者が受諾済みの組織への2件目のADMIN招待は受諾できず2人目の組織管理者を作らない() {
        PortalOrganization org = createBpOrg("role-second-" + unique());
        String firstEmail = "role-first-" + unique() + "@example.com";
        String secondEmail = "role-second-" + unique() + "@example.com";
        // 発行時の確認は組織管理者の「存在」を見るため、受諾前の2件は両方とも発行できる
        String firstToken = invite(org, firstEmail, "ADMIN");
        String secondToken = invite(org, secondEmail, "ADMIN");

        portalAuthService.acceptInvitation(acceptRequest(firstToken, firstEmail), new MockHttpServletRequest());
        BusinessException rejected = catchThrowableOfType(() -> portalAuthService.acceptInvitation(
                acceptRequest(secondToken, secondEmail), new MockHttpServletRequest()), BusinessException.class);

        assertThat(rejected).isNotNull();
        assertThat(rejected.getCode()).isEqualTo(409);
        assertThat(rejected.getMessageKey()).isEqualTo("error.portal.invite.orgAdminExists");
        assertThat(userMapper.selectByEmail(secondEmail)).as("2件目の受諾者を作らない").isNull();
        assertThat(usedAt(secondToken)).as("2件目の招待は未使用のまま残る").isNull();
        assertThat(orgAdminCount(org.getId())).isEqualTo(1);
        assertThat(permissionKeys(userMapper.selectByEmail(firstEmail).getId())).containsExactly(ORG_ADMIN);
    }

    @Test
    void 同じADMIN招待の重複受諾は409で組織管理者は1人のまま() {
        PortalOrganization org = createBpOrg("role-replay-" + unique());
        String email = "role-replay-" + unique() + "@example.com";
        String token = invite(org, email, "ADMIN");

        portalAuthService.acceptInvitation(acceptRequest(token, email), new MockHttpServletRequest());
        BusinessException replay = catchThrowableOfType(() -> portalAuthService.acceptInvitation(
                acceptRequest(token, email), new MockHttpServletRequest()), BusinessException.class);

        assertThat(replay).isNotNull();
        assertThat(replay.getCode()).isEqualTo(409);
        assertThat(replay.getMessageKey()).isEqualTo("error.portal.invite.used");
        assertThat(orgAdminCount(org.getId())).isEqualTo(1);
    }

    private String invite(PortalOrganization org, String email, String role) {
        String rawToken = "role-token-" + unique();
        PortalInvitation invitation = new PortalInvitation();
        invitation.setPortalOrgId(org.getId());
        invitation.setEmail(email);
        invitation.setRole(role);
        invitation.setTokenHash(SecurityHashUtil.sha256(rawToken));
        invitation.setExpiresAt(LocalDateTime.now().plusHours(72));
        invitationMapper.insert(invitation);
        return rawToken;
    }

    private static PortalAcceptInvitationRequest acceptRequest(String token, String email) {
        PortalAcceptInvitationRequest request = new PortalAcceptInvitationRequest();
        request.setToken(token);
        request.setEmail(email);
        request.setDisplayName("招待受諾者");
        request.setPassword(PASSWORD);
        return request;
    }

    private List<String> permissionKeys(Long userId) {
        return jdbcTemplate.queryForList("SELECT permission_key FROM t_portal_user_permission WHERE user_id = ? "
                + "ORDER BY permission_key", String.class, userId);
    }

    private int orgAdminCount(Long orgId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_portal_user_permission p "
                + "JOIN t_portal_user u ON u.id = p.user_id "
                + "WHERE u.portal_org_id = ? AND p.permission_key = ?", Integer.class, orgId, ORG_ADMIN);
    }

    private LocalDateTime usedAt(String rawToken) {
        return jdbcTemplate.queryForObject("SELECT used_at FROM t_portal_invitation WHERE token_hash = ?",
                LocalDateTime.class, SecurityHashUtil.sha256(rawToken));
    }

    private static String unique() {
        return Long.toString(System.nanoTime(), 36);
    }
}
