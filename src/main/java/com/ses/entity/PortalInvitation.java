package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * ポータル招待エンティティ（t_portal_invitation）。
 * 72時間有効・1回限り・tokenはSHA-256 hashのみ保存（平文は保存しない）。
 * 一回性はDB CAS（UPDATE ... WHERE used_at IS NULL）で保証する（design §6.3）。
 * 有効判定は used_at IS NULL だけでなく、期限・email一致・組織一致の4条件すべてを検証する（design §6.1）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_portal_invitation")
public class PortalInvitation extends BaseEntity {

    /** ポータル組織ID */
    private Long portalOrgId;

    /** 招待先email（このemailで受諾する） */
    private String email;

    /** 役割: MEMBER / ADMIN（組織管理者） */
    private String role;

    /** 招待tokenのSHA-256 hash（平文は保存しない） */
    private String tokenHash;

    /** 有効期限（既定72時間） */
    private LocalDateTime expiresAt;

    /** 使用日時。NULL=未使用（有効と同義ではない。期限を別途確認） */
    private LocalDateTime usedAt;

    /** 受諾後に作成されたportal user ID */
    private Long acceptedBy;

    /** 招待者（内部sys_user ID） */
    private Long invitedBy;
}
