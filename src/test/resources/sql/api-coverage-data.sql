INSERT INTO m_customer (id, company_name) VALUES (1, 'Test Customer');

INSERT INTO t_project (id, project_name, customer_id, status, start_date, end_date) 
VALUES (1, 'Test Project', 1, '募集中', '2024-04-01', '2025-03-31');

INSERT INTO t_engineer (id, full_name, status, expected_unit_price, employment_type, created_at) 
VALUES (1, 'Test Engineer', 'Bench', 800000, '正社員', '2024-04-01 10:00:00');

INSERT INTO t_contract (id, contract_no, engineer_id, project_id, customer_id, contract_type, status)
VALUES (1, 'C-001', 1, 1, 1, 'BP', '稼動中');

INSERT INTO t_work_record (id, contract_id, work_month, actual_hours, status)
VALUES (1, 1, '2024-04', 160.0, '確定');

INSERT INTO t_invoice (id, invoice_no, customer_id, billing_month, subtotal, tax, total, status)
VALUES (1, 'INV-001', 1, '2024-04', 800000, 80000, 880000, '未送付');

INSERT INTO t_sales_activity (id, customer_id, activity_type, activity_date, title)
VALUES (1, 1, '訪問', '2024-04-01', 'Test Activity');

INSERT INTO t_proposal (id, project_id, engineer_id, status)
VALUES (1, 1, 1, '提案中');

INSERT INTO m_skill_tag (id, skill_name, category)
VALUES (1, 'Java', 'Language');

INSERT INTO t_engineer_skill (id, engineer_id, skill_id, experience_years)
VALUES (1, 1, 1, 5);

INSERT INTO t_project_skill (id, project_id, skill_id, is_must)
VALUES (1, 1, 1, 1);

