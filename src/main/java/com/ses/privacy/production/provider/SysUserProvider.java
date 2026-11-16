package com.ses.privacy.production.provider;

import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ログインアカウント（{@code sys_user}、pii-inventory.md DB-001）。
 *
 * <p>本人の導出: {@code SYS_USER ← sys_user.id}。パスワード hash、ログイン失敗回数・ロック期限（認証の安全情報）、
 * パスワード変更要求、認可 epoch は allow-list に入れない（P3-8）。MFA の秘密・recovery code・session token は
 * 別表（{@code t_user_mfa} 等）にあり、本 provider は読まない。
 */
@Component
public class SysUserProvider implements ProductionPrivacyProvider {

    public static final String PROVIDER_ID = "sys-user";
    public static final String ACCOUNT = "SYS_USER_ACCOUNT";

    static final ProviderTable SYS_USER = new ProviderTable("sys_user", List.of(
            "id", "tenant_id", "username", "real_name", "email", "role", "status", "created_at", "updated_at",
            "deleted_flag"), List.of());

    private final PrivacyReadOnlyQuery query;

    public SysUserProvider(PrivacyReadOnlyQuery query) {
        this.query = query;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public List<ProviderDataElement> dataElements() {
        return List.of(ProviderDataElement.exact(ACCOUNT, SubjectAttribution.ATTRIBUTABLE,
                List.of(new SubjectLink(PrivacySubjectType.SYS_USER, "sys_user.id"))));
    }

    @Override
    public List<ProviderTable> tables() {
        return List.of(SYS_USER);
    }

    @Override
    public List<PrivacyRecord> findBySubject(String tenantId, PrivacySubjectRef subject) {
        ProviderRows.requireTenant(tenantId);
        if (subject.type() != PrivacySubjectType.SYS_USER) {
            return List.of();
        }
        List<PrivacyRecord> records = new ArrayList<>();
        for (Map<String, Object> row : query.select(SYS_USER, "tenant_id = :tenantId AND id = :subjectId",
                ProviderRows.params(tenantId, subject))) {
            records.add(ProviderRows.toRecord(PROVIDER_ID, SYS_USER, row, List.of(ACCOUNT), true));
        }
        return records;
    }
}
