package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 定例会・QBR記録エンティティ
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_customer_qbr")
public class CustomerQbr {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long customerId;

    private String title;

    private LocalDate meetingDate;

    private String attendees;

    private String agenda;

    private String discussion;

    private String decisions;

    private LocalDate nextMeetingDate;

    private Long createdBy;

    private Long updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
