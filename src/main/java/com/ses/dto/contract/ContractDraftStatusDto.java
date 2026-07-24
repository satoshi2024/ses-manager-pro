package com.ses.dto.contract;

import lombok.Data;

/**
 * 更新ドラフト（{@code renewed_from_contract_id} で元契約を指す契約）の状態のみを持つ軽量DTO。
 * 契約更新カレンダーの状態導出（DRAFT/CONFIRMED判定）専用。
 */
@Data
public class ContractDraftStatusDto {
    private Long renewedFromContractId;
    private String status;
}
