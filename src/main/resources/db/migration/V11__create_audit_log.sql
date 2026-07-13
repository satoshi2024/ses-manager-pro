-- ============================================================
-- SES Manager Pro - API操作監査ログ (P8フォローアップ・提案11)
-- ファイル: V11__create_audit_log.sql
-- 説明: /api/** への更新系リクエスト(POST/PUT/DELETE)の監査ログを永続化する。
--       アプリケーションログ(logging.level)はローテーションで消えるため、
--       管理者が後から参照できるようDBにも残す。
-- ============================================================

CREATE TABLE t_audit_log (
  id         BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  username   VARCHAR(50)                             COMMENT '実行者(未認証時はNULL)',
  method     VARCHAR(10)  NOT NULL                    COMMENT 'HTTPメソッド',
  uri        VARCHAR(500) NOT NULL                    COMMENT 'リクエストURI',
  status     INT          NOT NULL                    COMMENT 'レスポンスステータスコード',
  created_at DATETIME     DEFAULT CURRENT_TIMESTAMP   COMMENT '実行日時',

  INDEX idx_auditlog_username   (username),
  INDEX idx_auditlog_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='API操作監査ログ';

-- 監査ログ画面メニュー(管理者のみ)
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('audit-log', '監査ログ', '/audit-log', '/api/audit-logs', 96);

INSERT INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu WHERE menu_key = 'audit-log';
