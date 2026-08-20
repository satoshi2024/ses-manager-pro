-- ============================================================
-- SES Manager Pro - JP PINT Digital Invoice SEND UNIQUE (S16-P1-02)
-- ファイル: V108_3__digital_invoice_send_unique.sql
-- 説明: 有効な SEND 行について
--   UNIQUE(invoice_id, direction, profile, specification_version)
-- を生成列スロットで再導入する。
-- CANCELLED/REVOKED はスロット NULL のため再 Queue 可能。
-- CreditNote は profile が異なるため Standard と共存できる。
-- V107 / V107_2 は変更しない（V107_2 で DROP した制約の順方向修復）。
-- ============================================================

ALTER TABLE t_digital_invoice
  ADD COLUMN send_active_slot TINYINT
    GENERATED ALWAYS AS (
      CASE
        WHEN direction = 'SEND'
         AND deleted_flag = 0
         AND status NOT IN ('CANCELLED', 'REVOKED')
        THEN 1
        ELSE NULL
      END
    ) STORED COMMENT '有効SEND一意用。終端はNULLで再Queue可',
  ADD UNIQUE KEY uk_digital_invoice_send (
    invoice_id, direction, profile, specification_version, send_active_slot
  );
