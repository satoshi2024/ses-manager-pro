-- V79.1公開時点の注文・検収未導入shapeを固定するMySQL Testcontainers fixture。
-- 現在のV1統合baselineへ後発DDLがfold済みでも、V80 upgrade経路を真正な旧shapeから検証する。
ALTER TABLE t_contract DROP FOREIGN KEY fk_contract_order_line;
ALTER TABLE t_contract DROP CHECK chk_contract_acceptance_exemption;
ALTER TABLE t_contract DROP INDEX uk_contract_order_line;
ALTER TABLE t_contract DROP COLUMN acceptance_exemption_reason;
ALTER TABLE t_contract DROP COLUMN acceptance_required;
ALTER TABLE t_contract DROP COLUMN order_line_id;

DROP TABLE IF EXISTS t_document_hash_claim;
DROP TABLE IF EXISTS t_acceptance;
DROP TABLE IF EXISTS t_contract_acceptance_backfill;
DROP TABLE IF EXISTS t_sales_order_line;
DROP TABLE IF EXISTS t_sales_order;
