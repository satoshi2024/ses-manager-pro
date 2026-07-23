-- ===========================================
-- V43: スキルシート取込機能テーブル追加
-- ===========================================
CREATE TABLE t_resume_ingestion (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  original_file_name    VARCHAR(255) NOT NULL COMMENT 'アップロード時の元ファイル名',
  stored_file_name      VARCHAR(120) NOT NULL COMMENT '保存名(UUID.ext)。/api/files で参照',
  file_ext              VARCHAR(10)  NOT NULL COMMENT '拡張子(pdf/xlsx/docx)',
  status                VARCHAR(20)  NOT NULL DEFAULT '取込待ち'
                          COMMENT '取込待ち/抽出中/要確認/確定済/却下/失敗',
  extracted_text        LONGTEXT     NULL COMMENT '抽出したプレーンテキスト(PII)',
  parsed_json           LONGTEXT     NULL COMMENT 'AI構造化結果 + レビュー編集後の内容(JSON)',
  ai_provider           VARCHAR(30)  NULL COMMENT '解析に使ったプロバイダ(gemini/mock等)',
  ai_model              VARCHAR(60)  NULL COMMENT '解析に使ったモデル',
  error_message         VARCHAR(500) NULL COMMENT '失敗理由(サニタイズ済み)',
  converted_engineer_id BIGINT       NULL COMMENT '確定で生成した t_engineer.id',
  candidate_id          BIGINT       NULL COMMENT '候補者起点の場合の t_candidate.id(任意)',
  review_note           VARCHAR(500) NULL COMMENT 'レビュー担当メモ',
  created_at            DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag          TINYINT      NOT NULL DEFAULT 0 COMMENT '論理削除フラグ',
  created_by            BIGINT       NULL COMMENT '取込実行ユーザー',
  INDEX idx_ri_status (status),
  INDEX idx_ri_converted (converted_engineer_id),
  INDEX idx_ri_candidate (candidate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='スキルシート取込ジョブ';

-- メニュー登録（候補者 V16 に倉う。sort_order は候補者(67)付近の空き番へ）
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('resume-ingestion', 'スキルシート取込', '/resume-ingestion', '/api/resume-ingestions', 68);

INSERT INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu WHERE menu_key = 'resume-ingestion'
UNION ALL SELECT 'HR', id FROM m_menu WHERE menu_key = 'resume-ingestion';
