package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 月次締め状態（tenant×対象月）。
 * confirmed_at が非NULLなら締め済み。version は CAS 更新専用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_monthly_closing")
public class MonthlyClosing implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    /** YYYY-MM */
    private String workMonth;
    private Long confirmedBy;
    private LocalDateTime confirmedAt;
    /** CAS用。明示SQLで +1 する。 */
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
