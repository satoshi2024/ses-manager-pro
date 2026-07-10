package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * システムユーザーエンティティ
 * sys_userテーブルに対応するエンティティクラス
 * ログインユーザーの認証・認可情報を管理する
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * ユーザー名（ログインID）
     */
    private String username;

    /**
     * パスワード（BCryptハッシュ）
     */
    private String password;

    /**
     * 実名（表示名）
     */
    @TableField("real_name")
    private String realName;

    /**
     * ロール（管理者/営業/HR/マネージャー）
     */
    private String role;

    /**
     * メールアドレス
     */
    private String email;

    /**
     * ステータス（1: 有効, 0: 無効）
     */
    @Builder.Default
    private Integer status = 1;
}
