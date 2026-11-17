-- ============================================================
-- V200: t_document_version.storage_key に索引を付ける（NF-06 取込 stage 照合。FINAL review b-14、W2-NF06 review P3-5）。
--
-- ImportStageReconciler は照合台帳の1件ごとに
--   SELECT COUNT(*) FROM t_document_version WHERE storage_key = ?
-- を実行し（COMMIT 接続を確かめた後にもう一度数える）、同じ storage key を参照する版の有無で commit 済みか未commitかを決める。
-- 従来はこの列に索引が無く、台帳1件ごとに文書版の全件を走査していた。
--
-- 方針:
--   - V1・V67 の CREATE TABLE は変えない（適用済み migration を書き換えない）。本番は空DBへの新規導入で、この行は1回だけ実行される。
--   - 一意索引にしない。storage key は乱数（32桁hex/UUID）で版ごとに異なるが、照合は件数だけを見るため一意性を要求しない
--     （既存の行の重複の有無に依存させない）。
--   - storage_key は VARCHAR(500)・utf8mb4 で、索引の key 長は最大 2000 byte（InnoDB DYNAMIC の上限 3072 byte 以内）。
--   - H2 の試験 schema は sql/schema-document-archive-h2.sql が同じ索引を作る（@Sql で表を作り直す試験でも索引を保つ）。
-- ============================================================

CREATE INDEX idx_dv_storage_key ON t_document_version (storage_key);
