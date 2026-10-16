-- V1のDROP TABLEより先に、共有H2 DBに残り得る文書参照FKを解放する。
ALTER TABLE IF EXISTS t_import_job DROP CONSTRAINT IF EXISTS fk_import_job_document;
ALTER TABLE IF EXISTS t_import_job DROP CONSTRAINT IF EXISTS fk_import_job_document_version;
