-- ACC-ARCH-P1-003: コアエンティティ（要員・顧客・実績）に楽観ロック用 version を追加する。
-- 既存行は DEFAULT 0 で揃える。AFTER 句は付けず、環境差による列順差異を避ける。

ALTER TABLE t_engineer
  ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '楽観ロック';

ALTER TABLE m_customer
  ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '楽観ロック';

ALTER TABLE t_work_record
  ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '楽観ロック';
