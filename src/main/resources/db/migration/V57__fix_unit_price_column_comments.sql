-- ============================================================
-- SES Manager Pro - 単価カラムのコメント修正
-- 説明: t_project.unit_price_min/max と t_engineer.expected_unit_price の
--       COMMENT が「万円」となっていたが、実際の格納単位は「円」である
--       （画面の入力・表示、AI取込プロンプト、PriceFormatter いずれも円）。
--       この誤ったコメントがマッチスコアの単価採点を万円前提で実装させ、
--       円の値と比較して常に0点になる不具合の原因になったため、実態に合わせる。
--       型・NULL可否・既定値は変更しない（コメントのみ）。
-- ============================================================

ALTER TABLE t_project
  MODIFY COLUMN unit_price_min DECIMAL(10,0) COMMENT '単価下限(円/月)',
  MODIFY COLUMN unit_price_max DECIMAL(10,0) COMMENT '単価上限(円/月)';

ALTER TABLE t_engineer
  MODIFY COLUMN expected_unit_price DECIMAL(10,0) COMMENT '希望単価(円/月)';
