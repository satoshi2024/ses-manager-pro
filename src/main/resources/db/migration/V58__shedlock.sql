-- ============================================================
-- SES Manager Pro - バッチ多重起動防止（ShedLock）
-- 説明: 複数インスタンス構成で @Scheduled が全インスタンスで同時発火するのを防ぐ。
--       ShedLock 標準のロックテーブル定義（テーブル名・列名は固定）。
--       単一インスタンス運用でも常にロックを取得できるため動作は変わらない。
-- ============================================================

CREATE TABLE shedlock (
  name       VARCHAR(64)  NOT NULL COMMENT 'ロック名(バッチ識別子)',
  lock_until TIMESTAMP(3) NOT NULL COMMENT 'このロックの保持期限',
  locked_at  TIMESTAMP(3) NOT NULL COMMENT 'ロック取得時刻',
  locked_by  VARCHAR(255) NOT NULL COMMENT 'ロックを取得したインスタンス',
  PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='バッチ実行ロック(ShedLock)';
