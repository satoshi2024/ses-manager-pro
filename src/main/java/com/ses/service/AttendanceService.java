package com.ses.service;

import com.ses.dto.attendance.AttendanceDayRequest;
import com.ses.dto.attendance.AttendanceOverviewDto;

/** 雇用勤怠（t_employee_attendance/t_attendance_month）の業務境界。 */
public interface AttendanceService {

    AttendanceOverviewDto mine(String month);

    AttendanceOverviewDto management(String month);

    void saveMyDay(AttendanceDayRequest request);

    void deleteMyDay(String month, String workDate);

    void submitMyMonth(String month);

    void reject(Long engineerId, String month);

    void approve(Long engineerId, String month);

    void close(Long engineerId, String month);

    void reopen(Long engineerId, String month, String reason);
}
