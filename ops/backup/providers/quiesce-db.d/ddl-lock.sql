-- ses-backup:quiesce-db:ddl-lock
-- DDL 凍結の lock（design.md §7.6・§8）。保持 process がこの session を持ち続け、終わると MySQL が lock を返す。
-- Flyway の job の callback（ProductionBackupDdlFreezeFlywayCallback）と DBA の手作業の DDL は同じ名前の lock を取る。
-- 引数（検査済みの整数）: @p_lock_wait_seconds（lock を待つ上限の秒数）
-- 結果: DDL_LOCK <1 = 取れた / 0 = 待っても取れない / -1 = NULL（error）>
SELECT 'DDL_LOCK', COALESCE(GET_LOCK('ses_backup_ddl_freeze', @p_lock_wait_seconds), -1);
