-- ============================================================
-- SES Manager Pro - 債権管理（ar-management / P2）
-- ファイル: V28__ar_management.sql
-- 説明: 請求書入金テーブル t_invoice_payment を追加し、請求ステータスに
--       「一部入金」を追加する。m_customer.contact_email は既存のため追加不要。
-- ============================================================

CREATE TABLE t_invoice_payment (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  invoice_id   BIGINT NOT NULL COMMENT '対象請求書',
  paid_date    DATE NOT NULL COMMENT '入金日',
  amount       DECIMAL(12,0) NOT NULL COMMENT '入金額(円)',
  fee          DECIMAL(12,0) NOT NULL DEFAULT 0 COMMENT '振込手数料(円・当方負担の目減り)',
  remarks      VARCHAR(300),
  created_by   BIGINT,
  created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  FOREIGN KEY (invoice_id) REFERENCES t_invoice(id) ON DELETE RESTRICT
) COMMENT='請求書入金';

-- 請求ステータスに「一部入金」を追加。既定値・既存値は不変。
ALTER TABLE t_invoice MODIFY status ENUM('未送付','送付済','一部入金','入金済') DEFAULT '未送付' COMMENT 'ステータス';
