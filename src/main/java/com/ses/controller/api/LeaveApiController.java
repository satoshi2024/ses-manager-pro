package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.leave.LeaveApplyRequest;
import com.ses.dto.leave.LeaveApplicationResult;
import com.ses.dto.leave.LeaveBalanceDto;
import com.ses.dto.leave.LeaveDto;
import com.ses.dto.leave.LeaveGrantRequest;
import com.ses.entity.LeaveLedger;
import com.ses.service.LeaveService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 休暇申請・管理・付与・残数照会のAPI（T071/A2）。営業はSecurityConfigで到達できない。 */
@RestController
@RequiredArgsConstructor
public class LeaveApiController {

    private final LeaveService leaveService;

    @PostMapping("/api/my/leave")
    public ApiResult<LeaveApplicationResult> apply(@RequestBody LeaveApplyRequest request) {
        return ApiResult.success(leaveService.apply(request));
    }

    @GetMapping("/api/my/leave")
    public ApiResult<List<LeaveDto>> mine() {
        return ApiResult.success(leaveService.mine());
    }

    @PostMapping("/api/my/leave/{id}/cancel")
    public ApiResult<Void> cancel(@PathVariable Long id, @RequestBody LeaveCancelRequest request) {
        leaveService.cancel(id, request == null ? null : request.getReason());
        return ApiResult.success(null);
    }

    @PostMapping("/api/my/leave/{id}/resubmit")
    public ApiResult<Void> resubmit(@PathVariable Long id) {
        leaveService.resubmit(id);
        return ApiResult.success(null);
    }

    @GetMapping("/api/leave")
    public ApiResult<List<LeaveDto>> management(@RequestParam String month) {
        return ApiResult.success(leaveService.management(month));
    }

    @PostMapping("/api/leave/ledger/grants")
    public ApiResult<LeaveLedger> grant(@RequestBody LeaveGrantRequest request) {
        return ApiResult.success(leaveService.grant(request));
    }

    @GetMapping("/api/leave/ledger/balance")
    public ApiResult<List<LeaveBalanceDto>> balance(@RequestParam Long engineerId) {
        return ApiResult.success(leaveService.balance(engineerId));
    }

    /** 取消理由のcommand。内部entityへ公開しない。 */
    public static class LeaveCancelRequest {
        private String reason;

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }
}
