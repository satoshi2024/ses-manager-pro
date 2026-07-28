-- ============================================================
-- 組織階層・状態、および要員の会計属性を「日付で解決できる」ようにする（V61）
--
-- 背景（第十三次Review P1-1 / P1-5）:
--   * 組織統合は子組織の parent_id を直接書き換え、統合元の status を 無効 にする。
--     しかし listTree(asOf) / descendantIds(asOf) は現在の parent_id / status を読むため、
--     「昨日の組織ツリー」を問い合わせても今日の統合結果が返り、過去のscope・部門損益・
--     監査結果が後から変わってしまう。
--   * 月次締めが遅れた場合、Bench待機原価のsnapshotが要員の *現在の* 原価部門・想定単価を
--     読むため、締め前に要員を異動・単価改定すると確定済み扱いの過去月へ誤った値が焼き付く。
--
-- どちらも「現在値しか持たない列を過去日で参照している」ことが原因なので、
-- 変更履歴を別テーブルへ持ち、asOf で版を解決する。
-- 組織・要員の行自体はIDを分岐させない（所属・原価部門・予算・snapshotの参照を壊さないため。
-- design.md「属性更新は同一IDのversion CAS」を維持する）。
-- ============================================================

CREATE TABLE IF NOT EXISTS t_organization_relation_history (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  parent_id       BIGINT,
  status          VARCHAR(20) NOT NULL,
  valid_from      DATE NOT NULL,
  valid_to        DATE,
  created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag    TINYINT NOT NULL DEFAULT 0,
  INDEX idx_org_history_organization (organization_id, valid_from, valid_to),
  INDEX idx_org_history_parent (parent_id),
  CONSTRAINT fk_org_history_organization FOREIGN KEY (organization_id) REFERENCES m_organization_unit(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='組織の親子・状態の履歴';

CREATE TABLE IF NOT EXISTS t_engineer_accounting_history (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id         BIGINT NOT NULL,
  cost_center_id      BIGINT,
  expected_unit_price DECIMAL(12,0),
  valid_from          DATE NOT NULL,
  valid_to            DATE,
  created_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag        TINYINT NOT NULL DEFAULT 0,
  INDEX idx_engineer_accounting_history (engineer_id, valid_from, valid_to),
  CONSTRAINT fk_engineer_accounting_history_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='要員の会計属性(原価部門・想定単価)の履歴';

-- 既存行のbackfill。履歴が1件も無いと asOf 解決が「該当なし」になり、
-- V61適用直後に過去月の部門損益と待機原価がまとめて欠落する。
-- 開始日は組織自身の有効開始日（要員は十分過去の固定日）とし、現在値をそのまま初版とする。
INSERT INTO t_organization_relation_history (organization_id, parent_id, status, valid_from, valid_to)
SELECT o.id, o.parent_id, o.status, o.valid_from, NULL
FROM m_organization_unit o
WHERE o.deleted_flag = 0
  AND NOT EXISTS (
    SELECT 1 FROM t_organization_relation_history h WHERE h.organization_id = o.id
  );

INSERT INTO t_engineer_accounting_history (engineer_id, cost_center_id, expected_unit_price, valid_from, valid_to)
SELECT e.id, e.cost_center_id, e.expected_unit_price, '1970-01-01', NULL
FROM t_engineer e
WHERE e.deleted_flag = 0
  AND NOT EXISTS (
    SELECT 1 FROM t_engineer_accounting_history h WHERE h.engineer_id = e.id
  );
