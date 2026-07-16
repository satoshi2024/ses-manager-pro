-- テスト用(冪等): V27__money_flow_consistency.sql の t_invoice.tax_rate 追加相当を
-- 共有インメモリH2へ適用する。複数コンテキストで再実行されても失敗しないよう IF NOT EXISTS を用いる。
ALTER TABLE t_invoice ADD COLUMN IF NOT EXISTS tax_rate DECIMAL(4,3);
