package com.ses.portal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * portal認証済みprincipal。内部{@code LoginUser}とは別identity（G3・design §2）。
 * 内部LoginUserへ変換する経路は作らない（設計上の不変条件）。
 * 認可母集団はportal_org → customer_id / bp_company_id から独立に導出する（design §6.2）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortalLoginUser implements UserDetails {

    /** portal user ID */
    private Long portalUserId;

    /** ポータル組織ID */
    private Long portalOrgId;

    /** 組織種別: CUSTOMER / BP */
    private String orgType;

    /** 組織に紐づく顧客ID（CUSTOMER時。null可） */
    private Long customerId;

    /** 組織に紐づくBP会社ID（BP時。null可） */
    private Long bpCompanyId;

    private String email;

    private String displayName;

    /** MFA設定完了日時（session epoch検証に使用。MFA resetで全session失効） */
    private LocalDateTime mfaEnabledAt;

    /** 組織状態: ACTIVE / SUSPENDED */
    private String orgStatus;

    /** 本人状態: ACTIVE / SUSPENDED */
    private String userStatus;

    /** 組織管理者フラグ（invitation発行・user管理が可能） */
    private boolean orgAdmin;

    /** 個別付与権限キー（t_portal_user_permission） */
    private Set<String> permissions;

    /** 利用規約同意待ちフラグ（PortalSessionFilterが設定。同意画面へ強制遷移） */
    private transient boolean termsPending;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_PORTAL_USER"));
    }

    @Override
    public String getPassword() {
        return null; // パスワードは認証サービスでのみ検証し、principalに保持しない
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return "ACTIVE".equals(userStatus);
    }
}
