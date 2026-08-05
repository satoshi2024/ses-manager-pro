-- V79.1のFK action変更（CASCADE/SET NULL -> RESTRICT/RESTRICT）を、
-- 既にV79.1旧版が適用済みのDBへforwardで反映する運用手順。
--
-- 前提:
-- 1) backupまたは対象DBの復元点を確保する。
-- 2) flyway validateで不一致がversion=79.1だけであることを確認する。
-- 3) information_schema.REFERENTIAL_CONSTRAINTSで、旧版の
--    fk_approval_responsibility_orgがCASCADE/SET NULLであることを確認する。
--
-- 注意: このDDLを実行する前にflyway repairを実行してはいけない。
-- repairだけでは既存FKのactionは変更されず、旧schemaを新checksumで
-- 適用済みと誤認するためである。下記DDLを先に実行し、assert後に、
-- version=79.1だけを対象とするallowlistを再確認してからrepairする。
--
-- 再開可能性（information_schemaによる状態判定）:
-- MySQLのDDLは自動commitで個別に確定するため、途中失敗時は以下の
-- 3つのpartial状態のどれかで停止する。本スクリプトは各DDLの前に
-- information_schemaで現状態を判定し、未適用のDDLだけを実行するので、
-- どのpartial状態からでも再実行でき、常に最終schemaへ収束する（冪等）。
--   (A) DROP直後 : fk_approval_responsibility_orgが存在しない。
--                   -> (2)のFK追加と(3)のCHECK追加だけが実行される。
--   (B) FK追加直後: fk_approval_responsibility_orgがRESTRICT/RESTRICTで存在し、
--                   chk_approval_responsibility_organizationが存在しない。
--                   -> (3)のCHECK追加だけが実行される。
--   (C) CHECK追加直後: 両方が存在する（最終状態）。何も実行されない。
-- 各状態からの再実行後に、(4)の最終状態確認でFK action / CHECKを検証する。
--
-- 各中断点からの明示的復旧手順:
--   中断点(A) DROP後: 本スクリプトをそのまま再実行する（FK・CHECKを追加）。
--   中断点(B) FK追加後: 本スクリプトをそのまま再実行する（CHECKを追加）。
--   中断点(C) CHECK追加後: 本スクリプトをそのまま再実行しても安全（何もしない）。
-- いずれも再実行後に(4)で最終schemaを確認し、V79.1限定allowlistの
-- flyway repair -> validate を実施する。

-- (1) 旧版FK（CASCADE/SET NULL）が残っている場合だけDROPする。
--     既にRESTRICT/RESTRICTである場合と、既に存在しない場合（中断点A）は何もしない。
SET @drop_old_fk_sql = IF(
  EXISTS(
    SELECT 1
    FROM information_schema.REFERENTIAL_CONSTRAINTS rc
    WHERE rc.CONSTRAINT_SCHEMA = DATABASE()
      AND rc.TABLE_NAME = 't_approval_responsibility'
      AND rc.CONSTRAINT_NAME = 'fk_approval_responsibility_org'
      AND (rc.UPDATE_RULE <> 'RESTRICT' OR rc.DELETE_RULE <> 'RESTRICT')
  ),
  'ALTER TABLE t_approval_responsibility DROP FOREIGN KEY fk_approval_responsibility_org',
  'SELECT 1');
PREPARE drop_old_fk_stmt FROM @drop_old_fk_sql;
EXECUTE drop_old_fk_stmt;
DEALLOCATE PREPARE drop_old_fk_stmt;

-- (2) FKが存在しない場合（中断点A）だけ、RESTRICT/RESTRICTで追加する。
SET @add_fk_sql = IF(
  NOT EXISTS(
    SELECT 1
    FROM information_schema.REFERENTIAL_CONSTRAINTS rc
    WHERE rc.CONSTRAINT_SCHEMA = DATABASE()
      AND rc.TABLE_NAME = 't_approval_responsibility'
      AND rc.CONSTRAINT_NAME = 'fk_approval_responsibility_org'
  ),
  CONCAT('ALTER TABLE t_approval_responsibility ADD CONSTRAINT ',
         'fk_approval_responsibility_org FOREIGN KEY (organization_id) ',
         'REFERENCES m_organization_unit(id) ON UPDATE RESTRICT ON DELETE RESTRICT'),
  'SELECT 1');
PREPARE add_fk_stmt FROM @add_fk_sql;
EXECUTE add_fk_stmt;
DEALLOCATE PREPARE add_fk_stmt;

-- (3) CHECK制約が存在しない場合（中断点A/B）だけ追加する。
SET @add_check_sql = IF(
  NOT EXISTS(
    SELECT 1
    FROM information_schema.TABLE_CONSTRAINTS tc
    WHERE tc.CONSTRAINT_SCHEMA = DATABASE()
      AND tc.TABLE_NAME = 't_approval_responsibility'
      AND tc.CONSTRAINT_NAME = 'chk_approval_responsibility_organization'
      AND tc.CONSTRAINT_TYPE = 'CHECK'
  ),
  CONCAT('ALTER TABLE t_approval_responsibility ADD CONSTRAINT ',
         'chk_approval_responsibility_organization CHECK ',
         '(responsibility_type = ''FINANCE_MANAGER'' OR organization_id IS NOT NULL)'),
  'SELECT 1');
PREPARE add_check_stmt FROM @add_check_sql;
EXECUTE add_check_stmt;
DEALLOCATE PREPARE add_check_stmt;

-- (4) 最終状態の確認（手動実施。失敗時はここで止まる）
--     SELECT rc.CONSTRAINT_NAME, rc.UPDATE_RULE, rc.DELETE_RULE
--       FROM information_schema.REFERENTIAL_CONSTRAINTS rc
--       WHERE rc.CONSTRAINT_SCHEMA = DATABASE()
--         AND rc.TABLE_NAME = 't_approval_responsibility'
--         AND rc.CONSTRAINT_NAME = 'fk_approval_responsibility_org';
--     -- 期待: UPDATE_RULE=RESTRICT / DELETE_RULE=RESTRICT
--     SELECT tc.CONSTRAINT_NAME
--       FROM information_schema.TABLE_CONSTRAINTS tc
--       WHERE tc.CONSTRAINT_SCHEMA = DATABASE()
--         AND tc.TABLE_NAME = 't_approval_responsibility'
--         AND tc.CONSTRAINT_NAME = 'chk_approval_responsibility_organization'
--         AND tc.CONSTRAINT_TYPE = 'CHECK';
--     -- 期待: 1行
