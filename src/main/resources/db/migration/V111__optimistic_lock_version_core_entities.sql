-- ============================================================
-- V111: コアエンティティ（要員・顧客・実績）に楽観ロック用 version を追加する。
-- ACC-ARCH-P1-003 / REV-RP-P1-002
--
-- 既存行は DEFAULT 0 で揃える。AFTER 句は付けず、環境差による列順差異を避ける。
--
-- 【idempotent】information_schema + PREPARE で「列が無いときだけ ADD」。
-- 部分適用（例: t_engineer だけ成功して途中失敗）後の flyway repair → remigrate でも
-- Duplicate column で落ちない。V103 / V104_2 と同型。
--
-- 【checksum】本スクリプトは未公開（本番未適用）の V111 を冪等形へ書き換えるため
-- checksum が変わる。既に旧非冪等 V111 を適用済みの環境は想定しない。
-- failed history がある場合は repair 後の再 migrate で安全に完走できる。
-- U111（undo）は作らない。本変更は V111 の冪等 DDL。
-- ============================================================

-- ---- t_engineer.version ----
SET @v111_engineer_version_sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_engineer'
      AND COLUMN_NAME = 'version') = 0,
  'ALTER TABLE t_engineer ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT ''楽観ロック''',
  'SELECT 1');
PREPARE v111_engineer_version_stmt FROM @v111_engineer_version_sql;
EXECUTE v111_engineer_version_stmt;
DEALLOCATE PREPARE v111_engineer_version_stmt;

-- ---- m_customer.version ----
SET @v111_customer_version_sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'm_customer'
      AND COLUMN_NAME = 'version') = 0,
  'ALTER TABLE m_customer ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT ''楽観ロック''',
  'SELECT 1');
PREPARE v111_customer_version_stmt FROM @v111_customer_version_sql;
EXECUTE v111_customer_version_stmt;
DEALLOCATE PREPARE v111_customer_version_stmt;

-- ---- t_work_record.version ----
SET @v111_work_record_version_sql := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_work_record'
      AND COLUMN_NAME = 'version') = 0,
  'ALTER TABLE t_work_record ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT ''楽観ロック''',
  'SELECT 1');
PREPARE v111_work_record_version_stmt FROM @v111_work_record_version_sql;
EXECUTE v111_work_record_version_stmt;
DEALLOCATE PREPARE v111_work_record_version_stmt;
