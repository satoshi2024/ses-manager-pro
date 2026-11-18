package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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

    /** ユーザー母集団を資格期限通知のtenantへ固定する。 */
    private String tenantId;

    /**
     * ユーザー名（ログインID）
     */
    @NotBlank(message = "ユーザー名は必須です")
    @Size(min = 4, max = 50, message = "ユーザー名は4〜50文字で入力してください")
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

    /**
     * ログイン失敗回数
     */
    private Integer failedCount;

    /**
     * アカウントロック解除日時（未来日時ならロック中）
     */
    private java.time.LocalDateTime lockedUntil;

    /**
     * 初回ログイン時のパスワード変更要求（1: 本人が変更するまで変更画面・API以外を遮断、0: 通常）。
     * 本番の初期adminにだけ配備時のprod migrationが1を立て、本人のパスワード変更（ProfileApiController）で0へ戻す。
     * ユーザー管理APIの入力では変更しない（SysUserServiceImplが無視する）。
     */
    private Integer passwordChangeRequired;
}
