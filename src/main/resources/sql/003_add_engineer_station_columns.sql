-- ============================================================
-- SES Manager Pro - マイグレーション
-- ファイル: 003_add_engineer_station_columns.sql
-- 説明: 最寄り駅に加えて「都道府県」「鉄道会社・路線」を保存するための列を追加する。
--       既存 DB に対して実行する差分スクリプト（001 は新規構築用に別途更新済み）。
-- ============================================================

ALTER TABLE t_engineer
  ADD COLUMN prefecture      VARCHAR(50)  COMMENT '最寄り駅の都道府県'        AFTER nearest_station,
  ADD COLUMN railway_company VARCHAR(150) COMMENT '最寄り駅の鉄道会社・路線'  AFTER prefecture;
