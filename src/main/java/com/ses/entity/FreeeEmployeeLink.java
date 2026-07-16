package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.*;
import java.time.LocalDateTime;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("t_freee_employee_link")
public class FreeeEmployeeLink extends BaseEntity {
    private Long engineerId;
    private String freeeEmployeeId;
    private LocalDateTime confirmedAt;
    private Long confirmedBy;
}
