-- ============================================================
-- SES Manager Pro - レビュー指摘対応の設定シード
-- 説明: 契約更新エスカレーションの打ち切り日数（FR-06）と、
--       資金繰り警告バッチの予測月数（FR-05）を m_system_config に追加する。
--       いずれもコード側に既定値があるため、本シードは /system-config 画面から
--       編集できるようにすることが目的。
-- ============================================================

INSERT IGNORE INTO m_system_config (config_key, config_value, description) VALUES
  ('renewal.escalation-max-overdue-days', '90',
   '契約更新エスカレーションの打ち切り日数。終了日をこの日数以上超過した契約は通知対象外にする（0以下で無期限）。'),
  ('cashflow.alert-months', '6',
   '資金ショート警告バッチが先読みする月数。この範囲で残高が cashflow.alert-threshold を下回る月を通知する。');
