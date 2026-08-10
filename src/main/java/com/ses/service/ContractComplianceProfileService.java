package com.ses.service;

import com.ses.dto.compliance.ContractComplianceProfileDetailDto;
import com.ses.dto.compliance.ContractComplianceProfileSaveDto;

/**
 * 契約compliance profile（T063 A1）。
 * profileの取得・保存はroleに応じたfield maskを適用する（design §5.3）:
 *  - 管理者/HR: 全field（P0_FULL）
 *  - マネージャー: 待遇・保険・苦情詳細等はmask（P1_MASK）
 *  - 営業: 契約遂行に必要な限定fieldのみ（P2_LIMITED、書き込み不可）
 * maskは画面とAPIの両方で適用され、export/PDF（T064）と同一のallow-listを共有する。
 */
public interface ContractComplianceProfileService {

    /** 契約のcompliance profile詳細を取得する（profile・findings・workplace選択肢、role別mask済み）。 */
    ContractComplianceProfileDetailDto detail(Long contractId);

    /**
     * 契約のcompliance profileを保存する（full DTO。省略はreject、masked roleのsensitive変更はreject）。
     *
     * @param contractId 契約ID
     * @param rawBody    生JSON（key存在チェックのため）
     * @return 保存後の詳細（mask済み）
     */
    ContractComplianceProfileDetailDto save(Long contractId, String rawBody);
}
