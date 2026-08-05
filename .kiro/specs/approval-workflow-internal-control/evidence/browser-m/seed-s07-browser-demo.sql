-- S07 M browser Demo — reproduction seed fixture（dev profile / NoOpPasswordEncoder / plaintext password）
-- 適用: アプリ起動（Flyway migration完了）後に mysql client で適用。
--   docker exec -i ses-app-mysql mysql --default-character-set=utf8mb4 -uroot -p123456 ses_manager_db < seed-s07-browser-demo.sql
-- 再実行: 本ファイルは冪等（S07固有business keyだけを子→親順で削除後に再投入）。
--   - 全route/request/action/participant削除は行わない（S07のrequest_type集合に属する行のみ削除）。
--   - sys_userのsales1/mgr1は削除せずUPSERT（参照行のFKを壊さない）。
--   - AUTO_INCREMENTの固定IDに依存しない。IDはdemo2.jsがbusiness key（見積番号/契約番号/請求番号/BP支払key）からAPIで解決する。
SET NAMES utf8mb4;

-- ============ 1. S07固有business keyの削除（子→親順） ============
-- S07のapproval request_type集合（この集合はS07固有）
DROP TEMPORARY TABLE IF EXISTS tmp_s07_types;
CREATE TEMPORARY TABLE tmp_s07_types (request_type VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci PRIMARY KEY);
INSERT INTO tmp_s07_types (request_type) VALUES
  ('quotation.submit'),('quotation.accept'),('contract.activate'),('contract.revisePrice'),
  ('invoice.send'),('invoice.status'),('bp_payment.confirm'),('closing.confirm'),('closing.reopen');

-- 1a) S07 requestのaction / participant（requestの子）
DELETE a FROM t_approval_action a
JOIN t_approval_request r ON r.id = a.request_id
WHERE r.request_type IN (SELECT request_type FROM tmp_s07_types);

DELETE p FROM t_approval_participant p
JOIN t_approval_request r ON r.id = p.request_id
WHERE r.request_type IN (SELECT request_type FROM tmp_s07_types);

-- 1b) S07 request（親）
DELETE FROM t_approval_request WHERE request_type IN (SELECT request_type FROM tmp_s07_types);

-- 1c) 業務オブジェクト（子→親: bp_payment -> invoice_item -> invoice -> work_record -> contract -> quotation）
DELETE FROM t_bp_payment WHERE work_record_id IN (SELECT id FROM t_work_record WHERE remarks = 'S07ブラウザDemo用');
DELETE FROM t_invoice_item WHERE invoice_id IN (SELECT id FROM t_invoice WHERE invoice_no LIKE 'INV-202607-%');
DELETE FROM t_invoice WHERE invoice_no LIKE 'INV-202607-%';
DELETE FROM t_work_record WHERE remarks = 'S07ブラウザDemo用';
DELETE FROM t_contract WHERE contract_no IN ('C-2026-0001','C-2026-0002');
DELETE FROM t_quotation WHERE quotation_no IN ('Q-202608-0001','Q-202608-0002');

-- 1d) S07 route step -> route（routeはrequest_type集合で限定削除）
DELETE s FROM m_approval_route_step s
JOIN m_approval_route r ON r.id = s.route_id
WHERE r.request_type IN (SELECT request_type FROM tmp_s07_types);
DELETE FROM m_approval_route WHERE request_type IN (SELECT request_type FROM tmp_s07_types);

-- 1e) 締め済み月のS07 Demo月（2026-05 / 2026-04）だけを除去（他月のclosing記録は保持）。
--      現状がDemo月のみ（または空）の場合だけ空配列[]へ戻し、他月が含まれる場合は変更しない。
UPDATE m_system_config
SET config_value = '[]'
WHERE config_key = 'closing.confirmed-months'
  AND (
    config_value IS NULL OR config_value = '' OR config_value = '[]'
    OR (
      JSON_VALID(config_value)
      AND NOT EXISTS (
        SELECT 1
        FROM JSON_TABLE(config_value, '$[*]' COLUMNS (m VARCHAR(7) PATH '$.month')) jt
        WHERE jt.m IS NOT NULL AND jt.m NOT IN ('2026-05','2026-04')
      )
    )
  );

-- ============ 2. sys_user（削除せずUPSERT） ============
INSERT INTO sys_user (username, password, real_name, role, email, status)
VALUES ('sales1', 'sales123', '営業一郎', '営業', 'sales1@ses.local', 1),
       ('mgr1', 'mgr123', 'マネージャー太郎', 'マネージャー', 'mgr1@ses.local', 1)
ON DUPLICATE KEY UPDATE
  password = VALUES(password), real_name = VALUES(real_name), role = VALUES(role),
  email = VALUES(email), status = VALUES(status), deleted_flag = 0;

-- ============ 3. approval route（承認者=管理者ROLE、全対象種別・金額帯なし・全組織） ============
INSERT INTO m_approval_route (tenant_id, request_type, organization_id, min_amount, max_amount, version_no, valid_from, valid_to, active_flag, created_by)
SELECT 1, rt.request_type, NULL, NULL, NULL, 1, CURDATE(), NULL, 1, (SELECT id FROM sys_user WHERE username='admin')
FROM (SELECT 'quotation.submit' AS request_type UNION ALL SELECT 'quotation.accept' UNION ALL SELECT 'contract.activate' UNION ALL SELECT 'contract.revisePrice' UNION ALL SELECT 'invoice.send' UNION ALL SELECT 'invoice.status' UNION ALL SELECT 'bp_payment.confirm' UNION ALL SELECT 'closing.confirm' UNION ALL SELECT 'closing.reopen') rt;

INSERT INTO m_approval_route_step (route_id, step_no, parallel_group, approver_type, approver_value, sla_hours)
SELECT id, 1, 1, 'ROLE', '管理者', NULL FROM m_approval_route;

-- ============ 4. 業務オブジェクト（IDはAUTO_INCREMENT。固定IDに依存しない） ============
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
