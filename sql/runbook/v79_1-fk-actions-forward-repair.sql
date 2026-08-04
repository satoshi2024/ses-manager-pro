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

ALTER TABLE t_approval_responsibility
    DROP FOREIGN KEY fk_approval_responsibility_org;

ALTER TABLE t_approval_responsibility
    ADD CONSTRAINT fk_approval_responsibility_org FOREIGN KEY (organization_id)
        REFERENCES m_organization_unit(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

ALTER TABLE t_approval_responsibility
    ADD CONSTRAINT chk_approval_responsibility_organization
        CHECK (responsibility_type = 'FINANCE_MANAGER' OR organization_id IS NOT NULL);