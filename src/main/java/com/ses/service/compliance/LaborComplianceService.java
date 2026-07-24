package com.ses.service.compliance;

import com.ses.dto.compliance.ComplianceFinding;
import com.ses.dto.compliance.ContractComplianceDto;
import com.ses.entity.Contract;

import java.util.List;

/**
 * 偽装請負・多重派遣リスクチェック（FR-10）。
 * 判定は「警告」であり自動ブロックはしない。findings は都度導出し、永続化はしない。
 */
public interface LaborComplianceService {

    /** 指定契約についてリスクルールを検査し、該当したfindingsを返す（該当なしは空リスト）。 */
    List<ComplianceFinding> check(Contract contract);

    /** 現在リスク該当している契約を全件導出する（管理者向けリスク一覧画面用）。 */
    List<ContractComplianceDto> findCurrentRisks();
}
