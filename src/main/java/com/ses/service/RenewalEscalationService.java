package com.ses.service;

/**
 * 契約更新エスカレーション（FR-06）。
 * 更新期限のN日前になっても未対応（更新ドラフト未確定・継続/終了の明示判断なし）の契約を、
 * 段階設定（{@code m_system_config} の {@code renewal.escalation-days}）に従って
 * 担当営業→上長の順で通知する。
 */
public interface RenewalEscalationService {

    /**
     * 未対応の契約についてエスカレーション通知を発行する。
     * @return 発行した通知件数
     */
    int escalateUnhandled();
}
