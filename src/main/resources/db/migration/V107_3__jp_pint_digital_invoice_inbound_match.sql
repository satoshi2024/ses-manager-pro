ALTER TABLE t_digital_invoice
  ADD COLUMN supplier_company_id BIGINT NULL COMMENT '仕入先企業ID (照合結果)',
  ADD COLUMN purchase_order_id BIGINT NULL COMMENT '発注書ID (照合結果)',
  ADD COLUMN contract_id BIGINT NULL COMMENT '契約ID (照合結果)',
  ADD COLUMN match_status VARCHAR(20) NULL COMMENT '照合ステータス (MATCHED, UNMATCHED等)';
