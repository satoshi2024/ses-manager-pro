-- ============================================================
-- SES Manager Pro - 入金消込の半自動化（FR-09）
-- ファイル: V50__payment_reconciliation.sql
-- 説明: freee経由で取得した銀行入金明細を保持する t_bank_deposit を追加する。
--       突合候補（金額・名義・時期スコア）は永続化せず、GET /api/reconciliation/pending
--       で都度算出する（請求書残高の変動による突合結果の陳腐化を避けるため）。
--       status は 未消込→消込済 の一方向遷移のみ（候補提示/保留は未消込内の分類）。
-- ============================================================

CREATE TABLE t_bank_deposit (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  freee_deposit_id    VARCHAR(64) NOT NULL COMMENT 'freee側の入金明細ID(冪等キー・再取得時の重複防止)',
  deposit_date        DATE NOT NULL COMMENT '入金日',
  amount              DECIMAL(12,0) NOT NULL COMMENT '入金額(円)',
  payer_name          VARCHAR(200) COMMENT '振込名義(銀行明細上の表記)',
  status              ENUM('未消込','消込済') NOT NULL DEFAULT '未消込' COMMENT '消込状態',
  matched_invoice_id  BIGINT COMMENT '消込先請求書(消込済のみ)',
  matched_payment_id  BIGINT COMMENT '登録されたt_invoice_payment行(消込済のみ)',
  remarks             VARCHAR(300),
  created_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_bank_deposit_freee_id (freee_deposit_id),
  FOREIGN KEY (matched_invoice_id) REFERENCES t_invoice(id) ON DELETE RESTRICT,
  FOREIGN KEY (matched_payment_id) REFERENCES t_invoice_payment(id) ON DELETE RESTRICT
) COMMENT='銀行入金明細（freee連携・入金消込）';

INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('reconciliation', '入金消込', '/reconciliation', '/api/reconciliation', 91);

INSERT INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu WHERE menu_key = 'reconciliation'
UNION ALL SELECT 'マネージャー', id FROM m_menu WHERE menu_key = 'reconciliation';
