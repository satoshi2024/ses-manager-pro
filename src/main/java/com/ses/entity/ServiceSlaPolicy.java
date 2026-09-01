package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * SLAポリシーマスタエンティティ（m_service_sla_policy）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("m_service_sla_policy")
public class ServiceSlaPolicy {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** ポリシー名 */
    private String name;

    /** 優先度 (P0, P1, P2, P3) */
    private String priority;

    /** 初回応答目標時間 (時間) */
    private Integer responseTimeHours;

    /** 解決目標時間 (時間) */
    private Integer resolveTimeHours;

    /** 始業時刻 */
    private LocalTime businessHoursStart;

    /** 終業時刻 */
    private LocalTime businessHoursEnd;

    /** 休日を含むか (0:除外, 1:含む) */
    private Boolean includeHolidays;

    /** ステータス (ACTIVE, INACTIVE) */
    private String status;

    /** 楽観ロックバージョン */
    @Version
    private Integer version;

    /** 作成日時 */
    private LocalDateTime createdAt;

    /** 更新日時 */
    private LocalDateTime updatedAt;
}
