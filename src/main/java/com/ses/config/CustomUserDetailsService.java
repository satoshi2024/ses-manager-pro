package com.ses.config;

import com.ses.entity.SysUser;
import com.ses.mapper.SysUserMapper;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * カスタムユーザー認証サービス
 * データベースからユーザー情報を読み込み、Spring Securityの認証に使用する
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final SysUserMapper sysUserMapper;
    private final OidcSecurityProperties oidcSecurityProperties;

    /**
     * コンストラクタインジェクション
     *
     * @param sysUserMapper ユーザーマッパー
     * @param oidcSecurityProperties OIDC/local login設定
     */
    public CustomUserDetailsService(SysUserMapper sysUserMapper,
                                    OidcSecurityProperties oidcSecurityProperties) {
        this.sysUserMapper = sysUserMapper;
        this.oidcSecurityProperties = oidcSecurityProperties;
    }

    /**
     * ユーザー名でユーザー情報を読み込む
     * sys_userテーブルからユーザーを検索し、Spring SecurityのUserDetailsに変換する
     *
     * @param username ユーザー名
     * @return UserDetails 認証用ユーザー情報
     * @throws UsernameNotFoundException ユーザーが見つからない場合
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // データベースからユーザーを検索
        SysUser sysUser = sysUserMapper.selectByUsername(username);

        if (sysUser == null) {
            throw new UsernameNotFoundException("ユーザーが見つかりません: " + username);
        }
        boolean breakGlassLogin = oidcSecurityProperties.isBreakGlassLoginEnabled()
                && oidcSecurityProperties.isBreakGlassUsername(username);
        if (!oidcSecurityProperties.isLocalLoginEnabled() && !breakGlassLogin) {
            // IdP障害時の無制限local fallbackを防ぎ、明示されたbreak-glassだけを残す。
            throw new DisabledException("local login is disabled");
        }

        // ユーザーステータスの確認は LoginUser の isEnabled() で行われます

        // ロールをSpring SecurityのGrantedAuthorityにマッピング（ROLE_プレフィックス付き）
        SimpleGrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + sysUser.getRole());

        return new LoginUser(
            sysUser,
            Collections.singletonList(authority)
        );
    }
}
