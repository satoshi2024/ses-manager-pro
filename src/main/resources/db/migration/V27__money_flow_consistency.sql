-- ============================================================
-- SES Manager Pro - 金銭フロー整合性・契約ライフサイクル補完 (money-flow-consistency)
-- ファイル: V27__money_flow_consistency.sql
-- 説明: 単価カラムコメントの単位修正(万円→円)と、請求書への適用税率保存カラム追加。
--       V1 ベースラインは変更しない(チェックサム維持のため本マイグレーションのみで対応)。
-- ============================================================

-- R7-3: 単価カラムのコメント単位を「万円」→「円」へ修正(データ・型・NULL制約は現行どおり)。
ALTER TABLE t_engineer MODIFY expected_unit_price DECIMAL(10,0) NULL COMMENT '希望単価(円)';
ALTER TABLE t_project  MODIFY unit_price_min      DECIMAL(10,0) NULL COMMENT '単価下限(円)';
ALTER TABLE t_project  MODIFY unit_price_max      DECIMAL(10,0) NULL COMMENT '単価上限(円)';
ALTER TABLE t_proposal MODIFY proposed_unit_price DECIMAL(10,0) NULL COMMENT '提案単価(円)';

-- R8: 請求書生成時点の適用税率を保存する(小数。既存行は NULL のまま=設定値へフォールバック)。
ALTER TABLE t_invoice ADD COLUMN tax_rate DECIMAL(4,3) NULL COMMENT '適用税率(小数、生成時点)';
