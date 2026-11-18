-- V1のDROP TABLEより先に、共有H2 DBに残り得る文書参照FKを解放する。
ALTER TABLE IF EXISTS t_import_job DROP CONSTRAINT IF EXISTS fk_import_job_document;
ALTER TABLE IF EXISTS t_import_job DROP CONSTRAINT IF EXISTS fk_import_job_document_version;
-- NF01 B0（V195相当）: V1が作り直す sys_user・m_organization_unit・t_document_version へのFKを解放する。
-- 付け直しは schema-nf01-dual-confirmation-h2.sql が行う（t_lifecycle_case・t_lifecycle_task はV1が作り直さない）。
ALTER TABLE IF EXISTS t_lifecycle_dual_submission DROP CONSTRAINT IF EXISTS fk_dual_submission_submitter;
ALTER TABLE IF EXISTS t_lifecycle_dual_submission_document DROP CONSTRAINT IF EXISTS fk_dual_doc_version;
ALTER TABLE IF EXISTS t_lifecycle_dual_submission_org_fence DROP CONSTRAINT IF EXISTS fk_dual_org_unit;
ALTER TABLE IF EXISTS t_lifecycle_dual_confirmation DROP CONSTRAINT IF EXISTS fk_dual_confirmation_confirmer;
ALTER TABLE IF EXISTS t_lifecycle_dual_idempotency DROP CONSTRAINT IF EXISTS fk_dual_idem_actor;
