-- S07 M browser Demo — reproduction seed fixture（dev profile / NoOpPasswordEncoder / plaintext password）
-- 適用: アプリ起動（Flyway migration完了）後に mysql client で1回適用。
--   docker exec -i ses-app-mysql mysql --default-character-set=utf8mb4 -uroot -p123456 ses_manager_db < seed-s07-browser-demo.sql
-- 再実行（reset）: 各flowを再実行する前に本ファイルを再適用（DELETE/UPDATEで初期状態へ戻す）。
SET NAMES utf8mb4;

-- --- users（申請者 sales1=営業、mgr1=マネージャー。承認者 admin=管理者はV2 seed） ---
DELETE FROM sys_user WHERE username IN ('sales1','mgr1');
INSERT INTO sys_user (username, password, real_name, role, email, status) VALUES
  ('sales1', 'sales123', '営業一郎', '営業', 'sales1@ses.local', 1),
  ('mgr1', 'mgr123', 'マネージャー太郎', 'マネージャー', 'mgr1@ses.local', 1);

-- --- approval route（承認者=管理者ROLE、全対象種別・金額帯なし・全組織） ---
DELETE FROM m_approval_route_step;
DELETE FROM m_approval_route;
INSERT INTO m_approval_route (tenant_id, request_type, organization_id, min_amount, max_amount, version_no, valid_from, valid_to, active_flag, created_by)
SELECT 1, rt.request_type, NULL, NULL, NULL, 1, CURDATE(), NULL, 1, (SELECT id FROM sys_user WHERE username='admin')
FROM (SELECT 'quotation.submit' AS request_type UNION ALL SELECT 'quotation.accept' UNION ALL SELECT 'contract.activate' UNION ALL SELECT 'contract.revisePrice' UNION ALL SELECT 'invoice.send' UNION ALL SELECT 'invoice.status' UNION ALL SELECT 'bp_payment.confirm' UNION ALL SELECT 'closing.confirm' UNION ALL SELECT 'closing.reopen') rt;
INSERT INTO m_approval_route_step (route_id, step_no, parallel_group, approver_type, approver_value, sla_hours)
SELECT id, 1, 1, 'ROLE', '管理者', NULL FROM m_approval_route;

-- --- マスタ: 顧客 / 要員 / 案件（既存seedと衝突しないようID固定はしない。下記業務オブジェクトは1件目を利用） ---
DELETE FROM t_quotation WHERE quotation_no LIKE 'Q-202608-%';
DELETE FROM t_contract WHERE contract_no LIKE 'C-2026-000%';
DELETE FROM t_bp_payment WHERE work_record_id IN (SELECT id FROM t_work_record WHERE remarks='S07ブラウザDemo用');
DELETE FROM t_invoice_item WHERE invoice_id IN (SELECT id FROM t_invoice WHERE invoice_no LIKE 'INV-202607-%');
DELETE FROM t_invoice WHERE invoice_no LIKE 'INV-202607-%';
DELETE FROM t_work_record WHERE remarks='S07ブラウザDemo用';
DELETE FROM t_approval_action;
DELETE FROM t_approval_participant;
DELETE FROM t_approval_request;
UPDATE m_system_config SET config_value='[]' WHERE config_key='closing.confirmed-months';
UPDATE t_quotation SET status='下書き', version=0 WHERE id IN (1,2);
UPDATE t_contract SET status='準備中', version=0 WHERE id IN (2,3);
UPDATE t_invoice SET status='未送付' WHERE id IN (1,2);
UPDATE t_bp_payment SET status='未払', paid_date=NULL WHERE id IN (1,2);

-- --- 業務オブジェクト（desktop用 id=1系 / 390px用 id=2系、IDは既存seedとの整合のため上記UPDATEで初期化） ---
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

-- 勤怠実績(2026-07、入力中) — 請求/BP支払Demo用の土台（契約は1件目のcontract idを使用）
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
