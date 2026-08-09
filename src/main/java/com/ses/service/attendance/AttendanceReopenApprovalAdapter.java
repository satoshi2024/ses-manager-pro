package com.ses.service.attendance;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.AttendanceMonth;
import com.ses.entity.ApprovalRequest;
import com.ses.mapper.AttendanceMonthMapper;
import com.ses.service.approval.ApprovalSnapshot;
import com.ses.service.approval.ApprovalTargetAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 勤怠再openを既存approval engineへ接続するadapter。最終承認時だけ月次CASを実行する。 */
@Component
@RequiredArgsConstructor
public class AttendanceReopenApprovalAdapter implements ApprovalTargetAdapter {

    public static final String REQUEST_TYPE = "attendance.reopen";
    private static final String CLOSED = "締め済";
    private static final String APPROVED = "承認済";

    private final AttendanceMonthMapper attendanceMonthMapper;

    @Override
    public String requestType() {
        return REQUEST_TYPE;
    }

    @Override
    public ApprovalSnapshot snapshot(Long targetId, Map<String, Object> command) {
        AttendanceMonth month = month(targetId);
        if (!CLOSED.equals(month.getStatus())) {
            throw BusinessException.of(400, "error.attendance.invalidTransition", month.getStatus(), APPROVED);
        }
        String reason = command == null || command.get("reason") == null
                ? null : String.valueOf(command.get("reason"));
        requireReason(reason);
        return new ApprovalSnapshot(version(month), null, month.getOrganizationId(),
                command == null ? Map.of("reason", reason) : Map.copyOf(command),
                Map.of("beforeStatus", CLOSED, "afterStatus", APPROVED, "reason", reason));
    }

    @Override
    public long currentVersion(Long targetId) {
        return version(month(targetId));
    }

    @Override
    public void validateBeforeRequest(ApprovalSnapshot snapshot) {
        if (snapshot == null || snapshot.targetVersion() == null) {
            throw BusinessException.of(409, "error.attendance.concurrent");
        }
        Object reason = snapshot.payload() == null ? null : snapshot.payload().get("reason");
        requireReason(reason == null ? null : String.valueOf(reason));
    }

    @Override
    public void applyApproved(ApprovalRequest request) {
        if (request == null || request.getTargetId() == null || request.getTargetVersion() == null) {
            throw BusinessException.of(409, "error.attendance.concurrent");
        }
        int expectedVersion = request.getTargetVersion().intValue();
        int updated = attendanceMonthMapper.update(null, new UpdateWrapper<AttendanceMonth>()
                .set("status", APPROVED)
                .set("closed_at", null)
                .set("closed_by", null)
                .set("version", expectedVersion + 1)
                .set("updated_at", java.time.LocalDateTime.now())
                .eq("id", request.getTargetId())
                .eq("status", CLOSED)
                .eq("version", expectedVersion));
        if (updated != 1) {
            throw BusinessException.of(409, "error.attendance.concurrent");
        }
    }

    private AttendanceMonth month(Long targetId) {
        AttendanceMonth month = targetId == null ? null : attendanceMonthMapper.selectById(targetId);
        if (month == null) throw BusinessException.of(404, "error.attendance.monthNotFound");
        return month;
    }

    private long version(AttendanceMonth month) {
        return month.getVersion() == null ? 0L : month.getVersion().longValue();
    }

    private void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw BusinessException.of(400, "error.attendance.reopenReasonRequired");
        }
    }
}
