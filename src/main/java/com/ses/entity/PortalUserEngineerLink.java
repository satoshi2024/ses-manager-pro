package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * ポータルユーザーと要員の紐付け（t_portal_user_engineer_link。V188 / NF01 N1-4）。
 *
 * <p>退社自動処理は、管理者がポータル管理画面で登録したこの紐付けだけを対象にする（emailでは照合しない）。
 * 1ポータルユーザーの現行紐付け（{@code releasedAt == null}）は1件まで。解除は行を残して
 * {@code releasedAt} / {@code releasedBy} を記録する（履歴を論理削除で消さない）。
 * DB側の生成列 {@code active_portal_user_id} は一意判定専用のため、entityには持たない（書き込めない列）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_portal_user_engineer_link")
public class PortalUserEngineerLink extends BaseEntity {

    /** テナントID（ポータル組織・要員と同じtenant） */
    private String tenantId;

    /** ポータルユーザーID */
    private Long portalUserId;

    /** 要員ID */
    private Long engineerId;

    /** 紐付けを登録した管理者のユーザーID */
    private Long linkedBy;

    /** 紐付けの登録日時 */
    private LocalDateTime linkedAt;

    /** 紐付けを解除した管理者のユーザーID */
    private Long releasedBy;

    /** 紐付けの解除日時（NULL=現行の紐付け） */
    private LocalDateTime releasedAt;
}
