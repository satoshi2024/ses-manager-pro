package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
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
    
    private String activityType;
    
    private LocalDate activityDate;
    
    private String title;
    
    private String content;
    
    private LocalDate nextActionDate;
    
    private Integer completedFlag;
    
    private Long createdBy;
}
