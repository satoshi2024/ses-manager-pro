package com.ses.service;

import com.ses.dto.leave.LeaveApplyRequest;
import com.ses.dto.leave.LeaveApplicationResult;
import com.ses.dto.leave.LeaveBalanceDto;
import com.ses.dto.leave.LeaveDto;
import com.ses.dto.leave.LeaveGrantRequest;
import com.ses.entity.LeaveLedger;

import java.util.List;

/**
 * 休暇申請・承認統合（T071/A2）。R2.1/R2.2/R2.3の申請・残数・営業通知を担当する。
 * 残数モードは `leave.balance.source`（internal=G6既定/external）に従い、
 * 本システム正の場合は `t_leave_ledger` の残数CASで不足を拒否する（design §5.4）。
 */
public interface LeaveService {

    /** 本人申請。分計算・期間重複・残数・締め済み月を検証し、approval engineへ申請する。 */
    LeaveApplicationResult apply(LeaveApplyRequest request);

    /** 本人の申請一覧（自分のみ）。 */
    List<LeaveDto> mine();

    /** 管理一覧（HR=法人scope、マネージャー=組織scope、管理者=全件）。営業は不可視。 */
    List<LeaveDto> management(String month);

    /** 承認済休暇の取消申請（承認付き）。残数を戻す。 */
    void cancel(Long leaveId, String reason);

    /** HR/管理者が付与（GRANT）を台帳へ追加する。 */
    LeaveLedger grant(LeaveGrantRequest request);

    /** 残数照会（本システム正のみ。外部正はmode=externalで参照表示）。 */
    List<LeaveBalanceDto> balance(Long engineerId);
}
