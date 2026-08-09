package com.ses.dto.attendance;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 本人/管理画面共通の月次勤怠一覧DTO。 */
@Data
public class AttendanceOverviewDto {
    private String month;
    private List<AttendanceMonthDto> months = new ArrayList<>();
}
