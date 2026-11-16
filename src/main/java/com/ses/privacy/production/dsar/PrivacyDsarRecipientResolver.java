package com.ses.privacy.production.dsar;

import com.ses.service.security.AuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * DSAR 通知の宛先（production-bridge-phased-plan.md §3.5.1 の P3-9・§6.8）。
 *
 * <p>宛先は role ではなく {@code privacy-admin} group の所属者として解決する: 有効な group の所属者で、有効・未削除の
 * アカウントを候補にし、さらに {@link AuthorizationService#isAllowedForActor} が {@value #VIEW_ACTION} を許可する者だけを残す
 * （管理者 role 以外・{@code privacy.*} の拒否 group にも所属する者・設定 tenant 以外の者は落ちる）。管理者 role の全員には送らない。
 * {@code AuthorizationService} が取れない・判定が例外の場合は宛先に含めない（fail-closed）。権限表は読むだけで書かない。
 */
public class PrivacyDsarRecipientResolver {

    private static final Logger log = LoggerFactory.getLogger(PrivacyDsarRecipientResolver.class);

    static final String VIEW_ACTION = "privacy.dsar.view";
    static final String PRIVACY_ADMIN_GROUP = "privacy-admin";

    private static final String MEMBERS_SQL = """
            SELECT DISTINCT u.id, u.role, u.tenant_id
            FROM t_user_permission_group upg
            JOIN m_permission_group pg ON pg.id = upg.group_id
            JOIN sys_user u ON u.id = upg.user_id
            WHERE upg.tenant_id = ?
              AND upg.deleted_flag = 0
              AND pg.tenant_id = ?
              AND pg.group_key = ?
              AND pg.enabled = 1
              AND pg.deleted_flag = 0
              AND u.tenant_id = ?
              AND u.status = 1
              AND u.deleted_flag = 0
            ORDER BY u.id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<AuthorizationService> authorizationServiceProvider;

    public PrivacyDsarRecipientResolver(JdbcTemplate jdbcTemplate,
                                        ObjectProvider<AuthorizationService> authorizationServiceProvider) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.authorizationServiceProvider = authorizationServiceProvider;
    }

    /** tenant の宛先（sys_user.id の昇順）。 */
    public List<Long> resolve(String tenantId) {
        AuthorizationService authorizationService = authorizationServiceProvider == null
                ? null : authorizationServiceProvider.getIfAvailable();
        if (authorizationService == null) {
            log.warn("[privacy-dsar] 権限を確認できないため通知の宛先を解決しません");
            return List.of();
        }
        List<Member> members = jdbcTemplate.query(MEMBERS_SQL,
                (rs, rowNum) -> new Member(rs.getLong("id"), rs.getString("role"), rs.getString("tenant_id")),
                tenantId, tenantId, PRIVACY_ADMIN_GROUP, tenantId);
        List<Long> recipients = new ArrayList<>();
        for (Member member : members) {
            boolean allowed;
            try {
                allowed = authorizationService.isAllowedForActor(member.tenantId(), member.userId(), member.role(),
                        VIEW_ACTION);
            } catch (RuntimeException e) {
                log.warn("[privacy-dsar] 通知の宛先の権限を確認できないため除外します: exceptionClass={}",
                        e.getClass().getSimpleName());
                allowed = false;
            }
            if (allowed) {
                recipients.add(member.userId());
            }
        }
        return List.copyOf(recipients);
    }

    private record Member(long userId, String role, String tenantId) {
    }
}
