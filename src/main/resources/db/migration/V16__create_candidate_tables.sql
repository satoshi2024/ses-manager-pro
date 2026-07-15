-- ============================================================
-- SES Manager Pro - 求人・候補者採用パイプライン管理
-- 説明: 技術者採用（BP新規開拓・自社雇用エンジニア採用）の候補者パイプライン
--       (応募〜書類選考〜面談〜内定〜入社)を追跡する新規テーブル。
--       t_sales_activity(顧客営業)とは意図的にコード共有しない独立実装。
-- 仕様: .kiro/specs/recruiting-pipeline/
-- ============================================================

CREATE TABLE t_candidate (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  name                  VARCHAR(100) NOT NULL COMMENT '氏名',
  contact_email         VARCHAR(200) COMMENT '連絡先メール',
  contact_phone         VARCHAR(20) COMMENT '連絡先電話番号',
  skill_summary         VARCHAR(1000) COMMENT 'スキル概要',
  desired_rate          DECIMAL(10,0) COMMENT '希望単価',
  source                VARCHAR(50) COMMENT '情報源(紹介/エージェント/自社応募等)',
  current_stage         VARCHAR(20) NOT NULL DEFAULT '応募受付' COMMENT '非正規化: 最新ステージのキャッシュ',
  next_action_date      DATE COMMENT '次アクション予定日',
  converted_engineer_id BIGINT COMMENT '入社後のt_engineer.idへの紐付け',
  remarks               VARCHAR(1000) COMMENT '備考',
  deleted_flag          TINYINT NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
  created_by            BIGINT COMMENT '登録者ID',
  created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_candidate_current_stage (current_stage),
  INDEX idx_candidate_next_action (next_action_date),
  CONSTRAINT fk_candidate_converted_engineer FOREIGN KEY (converted_engineer_id) REFERENCES t_engineer(id) ON DELETE SET NULL,
  CONSTRAINT fk_candidate_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='採用候補者';

-- ステージ変更は必ずCandidateService経由で行うこと(Mapperの直接呼び出しは
-- t_candidate.current_stageとの非正規化ズレを生む。design.md 5章参照)。
CREATE TABLE t_candidate_activity (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  candidate_id BIGINT NOT NULL COMMENT '候補者ID',
  stage        VARCHAR(20) NOT NULL COMMENT '変更後ステージ',
  reason       VARCHAR(500) COMMENT '不採用/内定辞退時は必須(アプリ層で検証)',
  changed_by   BIGINT COMMENT '変更者ID',
  changed_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '変更日時',
  remarks      VARCHAR(500) COMMENT '備考',
  INDEX idx_candidate_activity_candidate (candidate_id),
  CONSTRAINT fk_candidate_activity_candidate FOREIGN KEY (candidate_id) REFERENCES t_candidate(id) ON DELETE RESTRICT,
  CONSTRAINT fk_candidate_activity_changed_by FOREIGN KEY (changed_by) REFERENCES sys_user(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='候補者ステージ変更履歴';

-- 候補者管理メニュー（管理者・営業・HR。採用は経営指標に直結するためマネージャーは対象外とする）
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('candidate', '候補者管理', '/candidate', '/api/candidates', 67)
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name);

INSERT INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu WHERE menu_key = 'candidate'
UNION ALL SELECT '営業', id FROM m_menu WHERE menu_key = 'candidate'
UNION ALL SELECT 'HR', id FROM m_menu WHERE menu_key = 'candidate';
