-- V172_1（dev seed専用。db/migration-dev）: V145のAI recommendation sample（run 85071）にtenantを補う（RB-PRERUN 4）。
--
-- V161は t_ai_recommendation_run.tenant_id をNULLのまま追加し（推測backfillなし）、V173は親runのtenantを
-- item/feedback/outcomeへ写し、写せない行が残ると「tenantを特定できないため移行を停止しました」で止まる。
-- devのseed（V145）はV161より前にtenant列の無いrunを1件入れているため、dev profile
-- （db/migration + db/migration-dev）で空DBから起動すると、V173で必ず停止していた。
--
-- dev seedのAI recommendation（run 85071 → item 85081 → feedback 85091 / outcome 85092）はtenant 'default' の
-- sampleなので、seedが入れたrunだけをIDとtrace_idで特定して 'default' を補い、V173の写しに任せる。
-- tenantが既に入っている行とseed以外の行は変更しない（再実行しても同じ結果になる）。
-- 適用済みのV145・V173はchecksumを変えないため編集しない。versionはV172とV173の間（172.1）に置き、V173より前に適用する。
UPDATE t_ai_recommendation_run
   SET tenant_id = 'default'
 WHERE id = 85071
   AND trace_id = '550e8400-e29b-41d4-a716-446655440001'
   AND (tenant_id IS NULL OR tenant_id = '');
