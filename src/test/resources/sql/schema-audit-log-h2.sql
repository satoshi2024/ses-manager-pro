-- テスト用: 監査ログテーブル(t_audit_log)を冪等に用意する。
-- 本番の V11__create_audit_log.sql と同一構造だが、共有インメモリH2(DB_CLOSE_DELAY=-1)を
-- 複数コンテキスト/複数スキーマ(engineer-schema-h2.sql の @Sql)が共有する都合上、
-- 既存でも失敗しないよう CREATE TABLE IF NOT EXISTS を用いる。
CREATE TABLE IF NOT EXISTS t_audit_log (
  id         BIGINT       AUTO_INCREMENT PRIMARY KEY,
  username   VARCHAR(50),
  method     VARCHAR(10)  NOT NULL,
  uri        VARCHAR(500) NOT NULL,
  status     INT          NOT NULL,
  created_at DATETIME     DEFAULT CURRENT_TIMESTAMP
);
