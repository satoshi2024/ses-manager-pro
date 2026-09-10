package com.ses.dto.security;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 修復担当者だけへ返す一時claim。queue entityをそのまま外部へ返さない。 */
@Getter
@AllArgsConstructor
public class OwnershipRepairClaimResponse {
    private Long queueId;
    private String claimToken;
    private Integer version;
}
