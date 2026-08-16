package com.ses.service.staffing;

import com.ses.entity.AllocationPlan;

import java.util.List;

/**
 * 要員配置計画の管理。区間代数・過配賦判定（日単位）・例外承認を担当する。
 *
 * <p>状態機械（design §5.4・確定済み）:
 * <pre>
 *   下書き → 確定 / 破棄
 *   確定 → 破棄 / 変更（新区間）
 * </pre>
 * <ul>
 *   <li>過配賦の判定は<b>日単位</b>（月平均にしない）。確定transaction内で対象要員の
 *       期間行をFOR UPDATEでロックしてから判定する（読んでから書くまでの競合を防ぐ）。</li>
 *   <li>同一期間の合計が100%超は原則拒否。例外は {@code exception_reason} +
 *       {@code approval_request_id} が必須（R2.2）。確定時には承認がapprovedであること。</li>
 *   <li>{@code position_id IS NULL} は「社内/待機」という業務値（未割当ではない。design §5.1）。</li>
 *   <li>区間境界は start/end ともinclusive、open end（end_date NULL）は計画window末（design §5.2）。</li>
 * </ul>
 */
public interface AllocationPlanService {

    /** 下書き保存（新規または既存下書きの上書き）。過配賦で例外理由が無ければ拒否。 */
    AllocationPlan saveDraft(AllocationPlan allocation);

    /** 確定（下書き→確定・状態CAS）。過配賦はロック付きで再検証し、例外は承認済みを要求。 */
    AllocationPlan confirm(Long id);

    /** 破棄（下書き|確定→破棄・状態CAS）。実契約由来（actual）は破棄できない。 */
    void discard(Long id);

    /**
     * 確定配置の変更（新区間）。旧区間を破棄（version CAS）し、新区間を下書き→確定する。
     * 失敗時はtransaction rollbackで変更前の区間へ戻る。
     */
    AllocationPlan revise(Long id, AllocationPlan newAllocation);

    AllocationPlan get(Long id);

    /** 要員の配置一覧（破棄済み含む。更新日時降順）。 */
    List<AllocationPlan> listByEngineer(Long engineerId);

    /** ポジションの配置一覧（破棄済み含む）。 */
    List<AllocationPlan> listByPosition(Long positionId);
}
