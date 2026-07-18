ALTER TABLE t_mail_delivery ADD COLUMN invoice_id BIGINT COMMENT '関連請求書ID';
CREATE INDEX idx_mail_delivery_invoice ON t_mail_delivery(invoice_id);