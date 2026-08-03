package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.*;

import java.time.LocalDate;

@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_sales_activity")
public class SalesActivity extends BaseEntity {

    private Long customerId;

    private Long contactId;

    private Long opportunityId;
    
    private String activityType;
    
    private LocalDate activityDate;
    
    private String title;
    
    private String content;
    
    private LocalDate nextActionDate;
    
    private Integer completedFlag;

    private Long assigneeUserId;

    @Version
    @Builder.Default
    private Integer version = 1;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
}
