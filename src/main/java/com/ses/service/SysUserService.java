package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.SysUser;
import org.springframework.security.core.Authentication;

/**
 * システムユーザーサービスインターフェース
 */
public interface SysUserService extends IService<SysUser> {

    /** ユーザー登録（権限グループ割当を含む）。 */
    void createUser(SysUser sysUser, Authentication authentication);

    /** ユーザー更新（ロール変更時の権限・scope失効を含む）。 */
    void updateUser(Long id, SysUser sysUser, Authentication authentication);

    /** 有効/無効切替（無効化時の所属クローズ・session失効を含む）。 */
    void updateUserStatus(Long id, Integer status, Authentication authentication);

    /** ユーザー削除（所属クローズ・session失効を含む）。 */
    void deleteUser(Long id, Authentication authentication);

    /**
     * 本人のパスワード変更。password（符号化済み）と初回パスワード変更要求（0へ戻す）の2列だけを更新する。
     * 認可列を書かないためactor epochは進めない（NF01計画 §4.1）。対象が無ければ404。
     */
    void changeOwnPassword(Long userId, String encodedPassword);
}
