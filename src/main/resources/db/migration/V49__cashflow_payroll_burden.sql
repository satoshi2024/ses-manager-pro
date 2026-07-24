-- V49__cashflow_payroll_burden.sql
-- 資金繰り予測：給与の事業主負担分（社会保険料の会社負担等）を総支給に上乗せする率
INSERT IGNORE INTO m_system_config (config_key, config_value, description) VALUES
('cashflow.payroll-employer-burden-rate', '0', '資金繰り予測：給与の事業主負担率(%)。総支給に対する社会保険料等の会社負担分を上乗せする。0=上乗せなし。');
