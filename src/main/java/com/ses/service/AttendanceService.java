package com.ses.service;

import com.ses.dto.attendance.AttendanceDayRequest;
import com.ses.dto.attendance.AttendanceDayDto;
import com.ses.dto.attendance.AttendanceOverviewDto;

import java.util.List;

/** 雇用勤怠（t_employee_attendance/t_attendance_month）の業務境界。 */
public interface AttendanceService {

    AttendanceOverviewDto mine(String month);

    AttendanceOverviewDto management(String month);

    /** 管理一覧の摘要ページング。日次明細は含めない。 */
    AttendanceOverviewDto management(String month, Long current, Long size);

    /** 管理画面向け: 1要員の日次明細を遅延取得する。 */
    List<AttendanceDayDto> managementDays(Long engineerId, String month);

    void saveMyDay(AttendanceDayRequest request);

    void deleteMyDay(String month, String workDate);

    void submitMyMonth(String month);

    void reject(Long engineerId, String month);

    void approve(Long engineerId, String month);

    void close(Long engineerId, String month);

    void reopen(Long engineerId, String month, String reason);
}
