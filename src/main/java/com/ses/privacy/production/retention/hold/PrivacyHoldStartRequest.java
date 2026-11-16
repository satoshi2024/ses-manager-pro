package com.ses.privacy.production.retention.hold;

import java.util.List;

/**
 * hold 開始の要求（{@code POST /api/privacy/holds}）。subject 参照は本文だけで受け取り、URL には入れない
 * （API の監査は URI を記録するため）。理由本文は保存せず SHA-256 だけを projection に残す。
 *
 * @param targetType            {@code SUBJECT} / {@code DATA_ELEMENT}
 * @param subjectRef            SUBJECT のとき {@code <種別>:<ID>}
 * @param dataElementId         DATA_ELEMENT のとき data element ID
 * @param reasonCode            理由コード（英大文字・数字・下線）
 * @param reasonText            理由本文（hash だけを保存する）
 * @param ticketRef             外部 ticket 参照
 * @param acknowledgePurgeStop  DATA_ELEMENT のとき、その element の purge が解除の開放まで止まることの確認（true 必須）
 * @param unattributableChoices SUBJECT のとき、帰属できない element ごとの選択（全 element に1件ずつ。既定値なし）
 */
public record PrivacyHoldStartRequest(String targetType, String subjectRef, String dataElementId, String reasonCode,
                                      String reasonText, String ticketRef, Boolean acknowledgePurgeStop,
                                      List<ElementChoice> unattributableChoices) {

    /** 帰属できない element の選択（{@code DATA_ELEMENT_HOLD} / {@code ACCEPT_RISK}）。 */
    public record ElementChoice(String dataElementId, String choice) {
    }
}
