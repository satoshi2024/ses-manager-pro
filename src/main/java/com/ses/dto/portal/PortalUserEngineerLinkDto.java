package com.ses.dto.portal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ポータル管理画面向けの、ポータルユーザーと要員の紐付けDTO（NF01 N1-4 / Q-10）。
 * 現行（{@code active=true}）と解除済みの履歴を同じ形で返す。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortalUserEngineerLinkDto {
    private Long id;
    private Long portalUserId;
    private Long engineerId;
    /** 要員名（同tenantの要員が見つからない場合はnull） */
    private String engineerName;
    private boolean active;
    private Long linkedBy;
    private LocalDateTime linkedAt;
    private Long releasedBy;
    private LocalDateTime releasedAt;
    /**
     * 登録時だけ設定する「要確認」: 要員の退社案件が既に完了している（未終結の退社案件が無い）。
     * 完了時の自動処理は遡って行われないため、ポータルユーザーの停止は管理者が個別に判断する。
     */
    private boolean engineerResigned;
}
