package com.ses.dto.portal;

import lombok.Data;

/**
 * 顧客portalの検収操作リクエスト（検収/差戻し共通）。
 */
@Data
public class PortalAcceptanceActionRequest {
    /** 検収時の顧客確認者ID（自顧客の有効な担当者のみ。省略可） */
    private Long customerContactId;
    /** 差戻し理由（差戻し時必須） */
    private String comment;
}
