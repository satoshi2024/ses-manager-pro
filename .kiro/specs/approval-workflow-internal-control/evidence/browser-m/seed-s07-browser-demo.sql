-- S07 M browser Demo — reproduction seed fixture（dev profile / NoOpPasswordEncoder / plaintext password）
-- 適用: アプリ起動（Flyway migration完了）後に mysql client で適用。
--   docker exec -i ses-app-mysql mysql --default-character-set=utf8mb4 -uroot -p123456 ses_manager_db < seed-s07-browser-demo.sql
-- 再実行: 本ファイルは冪等。
--   - 申請・routeをrequest_typeだけで削除しない。Demo business key（Q-202608-0001/0002、C-2026-0001/0002、
--     INV-202607-0001/0002、Demo BP支払、closing 2026-04/05）に対応するrequest IDだけを一時表へ抽出し、
--     action/participant -> request の順で削除する。
--   - routeにはDemo専用version（990001）を設定し、そのroute IDだけを削除・再投入する。
--     route stepのINSERTは新規Demo route ID（一時表）へ限定し、`SELECT ... FROM m_approval_route`による全route更新は行わない。
--   - invoice cleanupはLIKEではなくINV-202607-0001/0002の完全一致。
--   - closing JSONは他月のrecordを保持したまま2026-04/05だけを除去する。
--   - sys_userのsales1/mgr1は削除せずUPSERT（参照行のFKを壊さない）。
--   - AUTO_INCREMENTの固定IDに依存しない。IDはdemo2.jsがbusiness key（見積番号/契約番号/請求番号/BP支払key）からAPIで解決する。
SET NAMES utf8mb4;

-- Demo route専用の識別可能なversion marker（S07 seedが独占する値）
SET @demo_route_version = 990001;

-- ============ 1. Demo business key対応のrequest ID抽出と削除（子→親順） ============
DROP TEMPORARY TABLE IF EXISTS tmp_s07_demo_request;
CREATE TEMPORARY TABLE tmp_s07_demo_request (request_id BIGINT PRIMARY KEY);

-- 1a) 見積/契約/請求/BP支払/締めのDemo business keyに対応するrequest IDだけを抽出
INSERT IGNORE INTO tmp_s07_demo_request (request_id)
SELECT r.id FROM t_approval_request r
JOIN t_quotation q ON q.id = r.target_id AND r.target_type = 'QUOTATION'
WHERE q.quotation_no IN ('Q-202608-0001','Q-202608-0002');
INSERT IGNORE INTO tmp_s07_demo_request (request_id)
SELECT r.id FROM t_approval_request r
JOIN t_contract c ON c.id = r.target_id AND r.target_type = 'CONTRACT'
WHERE c.contract_no IN ('C-2026-0001','C-2026-0002');
INSERT IGNORE INTO tmp_s07_demo_request (request_id)
SELECT r.id FROM t_approval_request r
JOIN t_invoice i ON i.id = r.target_id AND r.target_type = 'INVOICE'
WHERE i.invoice_no IN ('INV-202607-0001','INV-202607-0002');
INSERT IGNORE INTO tmp_s07_demo_request (request_id)
SELECT r.id FROM t_approval_request r
JOIN t_bp_payment b ON b.id = r.target_id AND r.target_type = 'BP_PAYMENT'
WHERE b.payee_company_name = '株式会社BPデモ' AND b.layer_order IN (1,2);
INSERT IGNORE INTO tmp_s07_demo_request (request_id)
SELECT r.id FROM t_approval_request r
WHERE r.request_type = 'closing.confirm'
  AND JSON_UNQUOTE(JSON_EXTRACT(r.payload_json, '$.month')) IN ('2026-04','2026-05');

-- 1b) 抽出したrequest IDのaction / participant（requestの子）を削除
DELETE a FROM t_approval_action a
JOIN tmp_s07_demo_request t ON t.request_id = a.request_id;
DELETE p FROM t_approval_participant p
JOIN tmp_s07_demo_request t ON t.request_id = p.request_id;

-- 1c) 抽出したrequest（親）を削除
DELETE r FROM t_approval_request r
JOIN tmp_s07_demo_request t ON t.request_id = r.id;

-- ============ 2. Demo business objectの削除（子→親順） ============
DELETE FROM t_bp_payment WHERE work_record_id IN (SELECT id FROM t_work_record WHERE remarks = 'S07ブラウザDemo用');
DELETE FROM t_invoice_item WHERE invoice_id IN (SELECT id FROM t_invoice WHERE invoice_no IN ('INV-202607-0001','INV-202607-0002'));
DELETE FROM t_invoice WHERE invoice_no IN ('INV-202607-0001','INV-202607-0002');
DELETE FROM t_work_record WHERE remarks = 'S07ブラウザDemo用';
DELETE FROM t_contract WHERE contract_no IN ('C-2026-0001','C-2026-0002');
DELETE FROM t_quotation WHERE quotation_no IN ('Q-202608-0001','Q-202608-0002');

-- ============ 3. Demo route（version marker 990001）だけを削除・再投入 ============
-- 3a) 既存Demo route（version=990001）のstep -> routeを削除
DELETE s FROM m_approval_route_step s
JOIN m_approval_route r ON r.id = s.route_id
WHERE r.version_no = @demo_route_version;
DELETE FROM m_approval_route WHERE version_no = @demo_route_version;

-- 3b) 新規Demo routeを再投入し、route IDを一時表へ捕捉
DROP TEMPORARY TABLE IF EXISTS tmp_s07_demo_route;
CREATE TEMPORARY TABLE tmp_s07_demo_route (route_id BIGINT PRIMARY KEY);
INSERT INTO m_approval_route (tenant_id, request_type, organization_id, min_amount, max_amount, version_no, valid_from, valid_to, active_flag, created_by)
SELECT 1, rt.request_type, NULL, NULL, NULL, @demo_route_version, CURDATE(), NULL, 1, (SELECT id FROM sys_user WHERE username='admin')
FROM (SELECT 'quotation.submit' AS request_type UNION ALL SELECT 'quotation.accept' UNION ALL SELECT 'contract.activate' UNION ALL SELECT 'contract.revisePrice' UNION ALL SELECT 'invoice.send' UNION ALL SELECT 'invoice.status' UNION ALL SELECT 'bp_payment.confirm' UNION ALL SELECT 'closing.confirm' UNION ALL SELECT 'closing.reopen') rt;
INSERT INTO tmp_s07_demo_route (route_id)
SELECT id FROM m_approval_route WHERE version_no = @demo_route_version;

-- 3c) route stepは新規Demo route ID（一時表）だけへINSERT（全route更新はしない）
INSERT INTO m_approval_route_step (route_id, step_no, parallel_group, approver_type, approver_value, sla_hours)
SELECT route_id, 1, 1, 'ROLE', '管理者', NULL FROM tmp_s07_demo_route;

-- ============ 4. closing JSON: 他月を保持したまま2026-04/05だけを除去 ============
SET @closing_cfg = (SELECT config_value FROM m_system_config WHERE config_key = 'closing.confirmed-months');
DROP TEMPORARY TABLE IF EXISTS tmp_closing_keep;
CREATE TEMPORARY TABLE tmp_closing_keep AS
SELECT JSON_OBJECT('month', j.month, 'by', j.closed_by, 'at', j.at) AS rec
FROM JSON_TABLE(IFNULL(@closing_cfg, '[]'), '$[*]'
  COLUMNS (month VARCHAR(7) PATH '$.month', closed_by BIGINT PATH '$.by', at JSON PATH '$.at')) j
WHERE j.month NOT IN ('2026-04','2026-05');
UPDATE m_system_config
SET config_value = COALESCE((SELECT JSON_ARRAYAGG(rec) FROM tmp_closing_keep), '[]')
WHERE config_key = 'closing.confirmed-months';

-- ============ 5. sys_user（削除せずUPSERT） ============
INSERT INTO sys_user (username, password, real_name, role, email, status)
VALUES ('sales1', 'sales123', '営業一郎', '営業', 'sales1@ses.local', 1),
       ('mgr1', 'mgr123', 'マネージャー太郎', 'マネージャー', 'mgr1@ses.local', 1)
ON DUPLICATE KEY UPDATE
  password = VALUES(password), real_name = VALUES(real_name), role = VALUES(role),
  email = VALUES(email), status = VALUES(status), deleted_flag = 0;

-- ============ 6. 業務オブジェクト（IDはAUTO_INCREMENT。固定IDに依存しない） ============
-- マスタはmigration seed（V2等）に依存。顧客/要員/案件が存在しない環境では先に投入する。
INSERT INTO m_customer (company_name, company_name_kana, contact_person, contact_email, contact_phone, address, commercial_flow, trust_level, remarks)
SELECT '株式会社デモ商事', 'デモショウジ', '田中 太郎', 'tanaka@demo.example.jp', '03-1234-5678', '東京都千代田区丸の内1-1-1', '一次請', 'A', 'S07ブラウザDemo用'
WHERE NOT EXISTS (SELECT 1 FROM m_customer);
INSERT INTO t_engineer (full_name, full_name_kana, gender, employment_type, status, expected_unit_price, available_date, experience_years)
SELECT '鈴木 一郎', 'スズキ イチロウ', '男性', '正社員', 'Bench', 600000, '2026-07-01', 8
WHERE NOT EXISTS (SELECT 1 FROM t_engineer);
INSERT INTO t_project (project_name, customer_id, commercial_flow, description, required_count, unit_price_min, unit_price_max, work_location, remote_type, start_date, end_date, status, priority)
SELECT 'デモ商事 基幹システム開発', c.id, '一次請', 'S07ブラウザDemo用案件', 2, 50, 80, '東京都千代田区', 'ハイブリッド', '2026-07-01', '2027-03-31', '募集中', '通常'
FROM m_customer c
WHERE NOT EXISTS (SELECT 1 FROM t_project) LIMIT 1;

INSERT INTO t_quotation (quotation_no, customer_id, project_id, engineer_id, title, unit_price, settlement_hours_min, settlement_hours_max, valid_until, status, remarks, created_by)
SELECT 'Q-202608-0001', c.id, p.id, e.id, 'デモ商事 基幹システム開発（要員提供）', 600000, 140, 180, '2026-12-31', '下書き', 'S07ブラウザDemo用', u.id
FROM (SELECT id FROM m_customer ORDER BY id LIMIT 1) c, (SELECT id FROM t_project ORDER BY id LIMIT 1) p, (SELECT id FROM t_engineer ORDER BY id LIMIT 1) e, (SELECT id FROM sys_user WHERE username='admin') u;
INSERT INTO t_quotation (quotation_no, customer_id, project_id, engineer_id, title, unit_price, settlement_hours_min, settlement_hours_max, valid_until, status, remarks, created_by)
SELECT 'Q-202608-0002', c.id, p.id, e.id, 'デモ商事 基幹システム開発（要員提供・モバイル）', 600000, 140, 180, '2026-12-31', '下書き', 'S07ブラウザDemo用(390px)', u.id
FROM (SELECT id FROM m_customer ORDER BY id LIMIT 1) c, (SELECT id FROM t_project ORDER BY id LIMIT 1) p, (SELECT id FROM t_engineer ORDER BY id LIMIT 1) e, (SELECT id FROM sys_user WHERE username='admin') u;

INSERT INTO t_contract (contract_no, engineer_id, project_id, customer_id, contract_type, start_date, contract_date, job_description, work_location, selling_price, cost_price, auto_renew, status, remarks, created_by)
SELECT 'C-2026-0001', e.id, p.id, c.id, '準委任', '2026-07-01', '2026-06-15', 'デモ商事 基幹システム開発', '東京都千代田区', 600000, 480000, 1, '準備中', 'S07ブラウザDemo用', u.id
FROM (SELECT id FROM m_customer ORDER BY id LIMIT 1) c, (SELECT id FROM t_project ORDER BY id LIMIT 1) p, (SELECT id FROM t_engineer ORDER BY id LIMIT 1) e, (SELECT id FROM sys_user WHERE username='admin') u;
INSERT INTO t_contract (contract_no, engineer_id, project_id, customer_id, contract_type, start_date, contract_date, job_description, work_location, selling_price, cost_price, auto_renew, status, remarks, created_by)
SELECT 'C-2026-0002', e.id, p.id, c.id, '準委任', '2026-07-01', '2026-06-15', 'デモ商事 基幹システム開発', '東京都千代田区', 600000, 480000, 1, '準備中', 'S07ブラウザDemo用(390px)', u.id
FROM (SELECT id FROM m_customer ORDER BY id LIMIT 1) c, (SELECT id FROM t_project ORDER BY id LIMIT 1) p, (SELECT id FROM t_engineer ORDER BY id LIMIT 1) e, (SELECT id FROM sys_user WHERE username='admin') u;

-- 勤怠実績(2026-07、入力中) — 請求/BP支払Demo用の土台
INSERT INTO t_work_record (contract_id, work_month, actual_hours, billing_amount, payment_amount, status, remarks, created_by)
SELECT c.id, '2026-07', 160.0, 600000, 480000, '入力中', 'S07ブラウザDemo用', u.id
FROM t_contract c, (SELECT id FROM sys_user WHERE username='admin') u WHERE c.contract_no='C-2026-0001';

INSERT INTO t_invoice (invoice_no, customer_id, billing_month, subtotal, tax, total, status, issued_date, due_date, remarks, created_by)
SELECT 'INV-202607-0001', c.id, '2026-07', 600000, 60000, 660000, '未送付', NULL, '2026-08-31', 'S07ブラウザDemo用', u.id
FROM m_customer c, (SELECT id FROM sys_user WHERE username='admin') u ORDER BY c.id LIMIT 1;
INSERT INTO t_invoice (invoice_no, customer_id, billing_month, subtotal, tax, total, status, issued_date, due_date, remarks, created_by)
SELECT 'INV-202607-0002', c.id, '2026-07', 600000, 60000, 660000, '未送付', NULL, '2026-08-31', 'S07ブラウザDemo用(390px)', u.id
FROM m_customer c, (SELECT id FROM sys_user WHERE username='admin') u ORDER BY c.id LIMIT 1;

INSERT INTO t_invoice_item (invoice_id, work_record_id, description, amount)
SELECT i.id, w.id, CONCAT(e.full_name, '（デモ商事 基幹システム開発）'), 600000
FROM t_invoice i, t_work_record w, t_engineer e WHERE i.invoice_no='INV-202607-0001' AND w.remarks='S07ブラウザDemo用' ORDER BY e.id LIMIT 1;

INSERT INTO t_bp_payment (work_record_id, layer_order, payee_company_name, parent_payment_id, amount, status, paid_date, remarks)
SELECT w.id, 1, '株式会社BPデモ', NULL, 480000, '未払', NULL, 'S07ブラウザDemo用' FROM t_work_record w WHERE w.remarks='S07ブラウザDemo用' ORDER BY w.id LIMIT 1;
INSERT INTO t_bp_payment (work_record_id, layer_order, payee_company_name, parent_payment_id, amount, status, paid_date, remarks)
SELECT w.id, 2, '株式会社BPデモ', NULL, 480000, '未払', NULL, 'S07ブラウザDemo用(390px)' FROM t_work_record w WHERE w.remarks='S07ブラウザDemo用' ORDER BY w.id LIMIT 1;
