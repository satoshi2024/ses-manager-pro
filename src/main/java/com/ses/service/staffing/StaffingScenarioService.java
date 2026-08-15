package com.ses.service.staffing;

import com.ses.entity.StaffingScenario;
import com.ses.entity.StaffingScenarioAllocation;

import java.util.List;

/**
 * 仮配置シナリオの管理（R3.3・本データを変更しない）。
 *
 * <p>scenario操作は {@code t_staffing_scenario} / {@code t_staffing_scenario_allocation} のみを
 * 更新し、{@code t_allocation_plan}・契約・提案へ一切書き込まない。
 * 可視性: owner本人 ＋ {@code shared_flag=1} なら同一組織scope内（design §5.3。
 * 組織scopeのfilterはB2で実装し、本serviceはowner/共有フラグを最低限のgateとする）。
 */
public interface StaffingScenarioService {

    /** 作成。ownerは現在ユーザーに固定する。 */
    StaffingScenario create(StaffingScenario scenario);

    /** 更新（ownerのみ）。 */
    StaffingScenario update(StaffingScenario scenario);

    /** 削除（ownerのみ。シナリオとその配置を論理削除）。 */
    void delete(Long id);

    /** 参照（ownerまたは共有）。 */
    StaffingScenario get(Long id);

    /** ownerまたは共有の一覧。 */
    List<StaffingScenario> listVisible();

    /** 仮配置の保存（新規または上書き）。datesはISO日付JSON配列（昇順・重複なし・[base_date, +24か月]）。 */
    StaffingScenarioAllocation upsertAllocation(StaffingScenarioAllocation allocation);

    /** 仮配置の削除（シナリオへの閲覧権限が必要）。 */
    void deleteAllocation(Long allocationId);

    /** シナリオの仮配置一覧。 */
    List<StaffingScenarioAllocation> listAllocations(Long scenarioId);
}
