package com.ses.service.compliance;

import com.ses.dto.compliance.ComplianceFinding;
import com.ses.entity.Contract;

import java.util.List;

/**
 * 派遣・準委任コンプライアンス検査rule（design §2）。
 * 既存4 rule（TIER_EXCEEDED / DIRECT_COMMAND / DOUBLE_DISPATCH / SETTLEMENT_MISMATCH）のcode/挙動を維持し、
 * 新ruleはMISSING_* / DEADLINE_* / RISK_* を返す。rule実行はread-only＋findingのupsertのみで、
 * 契約や勤怠の業務状態を変更しない。
 */
public interface ComplianceRule {

    /** finding code（回帰testのgolden fixtureと一致させる） */
    String code();

    /** 判定対象かどうかをcode単位で確認（例: 派遣のみ） */
    boolean appliesTo(Contract contract);

    /** 対象契約のfinding候補を返す（永続化は ComplianceRuleEngine が行う） */
    List<ComplianceFinding> evaluate(Contract contract, ComplianceRuleContext context);
}
