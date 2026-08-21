-- ============================================================
-- SES Manager Pro - S09-P1-01
-- ファイル: V108_2__sales_order_quotation_unique.sql
-- 説明: t_sales_order.quotation_id に nullable UNIQUE を追加し、
--       見積→注文の並行二重生成をDB最終防衛線で防ぐ。
-- V108 / V108.1 は変更しない。欠番埋めはしない。
-- ============================================================

-- 先行競合でできた重複は最小 id を残し、他は quotation_id を NULL 化してから UNIQUE を張る。
-- （手動作成注文はもともと quotation_id NULL 可。複数 NULL は UNIQUE で許可される）
UPDATE t_sales_order o
INNER JOIN (
    SELECT quotation_id, MIN(id) AS keep_id
    FROM t_sales_order
    WHERE quotation_id IS NOT NULL
    GROUP BY quotation_id
    HAVING COUNT(*) > 1
) d ON o.quotation_id = d.quotation_id AND o.id <> d.keep_id
SET o.quotation_id = NULL;

SET @uk_sales_order_quotation_sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema = DATABASE()
        AND table_name = 't_sales_order'
        AND index_name = 'uk_sales_order_quotation') = 0,
    'ALTER TABLE t_sales_order ADD UNIQUE KEY uk_sales_order_quotation (quotation_id)',
    'SELECT 1'
);
PREPARE uk_sales_order_quotation_stmt FROM @uk_sales_order_quotation_sql;
EXECUTE uk_sales_order_quotation_stmt;
DEALLOCATE PREPARE uk_sales_order_quotation_stmt;
