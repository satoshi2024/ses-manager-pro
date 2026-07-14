-- ============================================================
-- SES Manager Pro - マイグレーション
-- ファイル: 003_add_engineer_station_columns.sql
-- 説明: 最寄り駅の「都道府県(prefecture)」「鉄道会社・路線(railway_company)」列。
--
-- 【no-op】これらの列は統合ベーススキーマ V1(001) の CREATE TABLE t_engineer に
--          既に定義済みのため、本スクリプトは ADD COLUMN を行わない no-op とする。
--          かつては既存DB向けの差分 ADD COLUMN だったが、V1へ折り込み済みの現在は、
--          空DBからの逐次リプレイ（本番 Flyway / test の spring.sql.init）で
--          『Duplicate column』になるのを防ぐため実行文を除去した。
--          本番デプロイは baseline-version=9 により V1〜V9 を再実行しないため影響しない。
--          ※ spring.sql.init は空スクリプトを許容しないため、無害な SELECT 1 を残す。
-- ============================================================

SELECT 1;
