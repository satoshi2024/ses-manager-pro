-- R3R-13 (C5): 工数精度を日次(DECIMAL(4,2))・月次(DECIMAL(5,1))で統一する。
-- 日次合計と月次工数・請求/支払計算の基準を小数2桁へ揃える。
-- MODIFYは同型への変更でもエラーにならないため、空DB・既存DBの双方で安全。
ALTER TABLE t_work_record MODIFY COLUMN actual_hours DECIMAL(6,2) NOT NULL;
ALTER TABLE t_work_record_daily MODIFY COLUMN worked_hours DECIMAL(6,2) NOT NULL;
