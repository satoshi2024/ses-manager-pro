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

/** 勤務カレンダーの日別定義。scheduledMinutesのNULLと0は意味が異なる。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("m_work_calendar_day")
public class WorkCalendarDay {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long calendarId;
    private LocalDate calendarDate;
    private String dayType;
    private Integer scheduledMinutes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
