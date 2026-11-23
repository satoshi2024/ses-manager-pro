-- FE-B01・FE-B02（feature-enablement-console requirements §7「機能を閉じる」、復審 rb-fe-b-656d71b8 の P3-4）: prod の完全な context
-- （ProductionSecurityConfigurationTest）で、NF01 本体・NF01 二者確認・NF06 が DB で開いていて、要る署名は全て有効期間の内
-- （openUntil が未来で、起動の時点の実効値は開）なのに、固有の条件を満たさない状態から起動する。
--   - NF01 二者確認: Privacy 署名 NF01_DUAL_PRIVACY の参照が placeholder（TODO-1）。以前は prod の起動時検査
--     （LifecycleDualConfirmationReleaseGate）が起動を止めた。
--   - NF06: admission の署名 NF06_ADMISSION に構造化された値が無い（決定 ID・承認 tenant・source code・digest が無い）。行参照の鍵は
--     test 用の鍵で prod では使えない。以前は ImportAdmissionStartupValidator・ImportRowReferenceKeyStartupValidator が起動を止めた。
-- 値は全て合成。class 専用の H2 へ context の初期化で1回だけ投入する（冪等）。ID は段階 C の seed（980001・980201〜・980301）と
-- 重ならない範囲にする。
INSERT INTO t_feature_enablement_request (id, tenant_id, request_type, target_key, status, condition_fingerprint,
    condition_summary, requested_by, requested_at, expires_at, decided_by, decided_at, version)
SELECT v.id, 'default', 'OPEN', v.target_key, 'APPROVED', REPEAT('f', 64), 'fixture', 980101,
    TIMESTAMP '2026-11-20 09:00:00', TIMESTAMP '2026-11-27 09:00:00', 980102, TIMESTAMP '2026-11-20 10:00:00', 1
FROM (VALUES (980011, 'NF01'), (980012, 'NF01_DUAL_CONFIRMATION'), (980013, 'NF06')) AS v(id, target_key)
WHERE NOT EXISTS (SELECT 1 FROM t_feature_enablement_request r WHERE r.id = v.id);

INSERT INTO t_feature_enablement_state (tenant_id, feature_key, state, version, open_request_id, opened_by, approved_by,
    opened_at, updated_at)
SELECT 'default', v.feature_key, 'OPEN', 1, v.request_id, 980101, 980102, TIMESTAMP '2026-11-20 10:00:00',
    TIMESTAMP '2026-11-20 10:00:00'
FROM (VALUES ('NF01', 980011), ('NF01_DUAL_CONFIRMATION', 980012), ('NF06', 980013)) AS v(feature_key, request_id)
WHERE NOT EXISTS (SELECT 1 FROM t_feature_enablement_state s WHERE s.tenant_id = 'default'
    AND s.feature_key = v.feature_key);

INSERT INTO t_feature_sign_off (id, tenant_id, kind_code, reference, signer_role, signed_on, expires_on, kind_class,
    content_sha256, size_bytes, stored_name, status, registered_by, registered_at, confirmed_by, confirmed_at)
SELECT v.id, 'default', v.kind_code, v.reference, v.signer_role, DATE '2020-01-01', DATE '2099-12-31', v.kind_class,
    REPEAT('a', 64), 10, v.stored_name, 'CONFIRMED', 980101, TIMESTAMP '2026-11-01 10:00:00', 980102,
    TIMESTAMP '2026-11-01 11:00:00'
FROM (VALUES
    (980401, 'NF01_PLAN_SECURITY', 'SEC-NF01-FIXTURE-01', 'IDENTITY_SECURITY', 'DECISION', 'fe-b-nf01-01.pdf'),
    (980402, 'NF01_PLAN_SRE', 'SRE-NF01-FIXTURE-02', 'SRE', 'DECISION', 'fe-b-nf01-02.pdf'),
    (980403, 'NF01_PLAN_DOCUMENT_OWNER', 'DOC-NF01-FIXTURE-03', 'DOCUMENT_OWNER', 'DECISION', 'fe-b-nf01-03.pdf'),
    (980404, 'NF01_PLAN_HR', 'HRD-NF01-FIXTURE-04', 'HR', 'DECISION', 'fe-b-nf01-04.pdf'),
    (980405, 'NF01_PLAN_DBA', 'DBA-NF01-FIXTURE-05', 'DBA', 'DECISION', 'fe-b-nf01-05.pdf'),
    (980406, 'NF01_STAGING_VERIFICATION', 'STG-NF01-FIXTURE-06', 'PRODUCTION_OWNER', 'EVIDENCE', 'fe-b-nf01-06.pdf'),
    (980407, 'NF01_RUNNER_EVIDENCE', 'RUN-NF01-FIXTURE-07', 'TECH_LEAD', 'EVIDENCE', 'fe-b-nf01-07.pdf'),
    (980408, 'NF01_DUAL_PRIVACY', 'TODO-1', 'PRIVACY_OWNER', 'DECISION', 'fe-b-dual-01.pdf'),
    (980409, 'NF01_DUAL_MANUAL_CHECK', 'MAN-DUAL-FIXTURE-02', 'PRODUCTION_OWNER', 'EVIDENCE', 'fe-b-dual-02.pdf'),
    (980410, 'NF01_DUAL_BREAK_GLASS_DECISION', 'BGD-DUAL-FIXTURE-03', 'SECURITY', 'DECISION', 'fe-b-dual-03.pdf'),
    (980411, 'NF01_DUAL_RUNNER_EVIDENCE', 'RUN-DUAL-FIXTURE-04', 'TECH_LEAD', 'EVIDENCE', 'fe-b-dual-04.pdf'),
    (980412, 'NF01_DUAL_REVIEW_PASS', 'REV-DUAL-FIXTURE-05', 'TECH_LEAD', 'EVIDENCE', 'fe-b-dual-05.pdf'),
    (980413, 'NF06_ADMISSION', 'DO-NF06-FIXTURE-0001', 'DATA_OWNER', 'DECISION', 'fe-b-nf06-01.pdf'),
    (980414, 'NF06_PRIVACY_RAW_CSV', 'PRV-NF06-FIXTURE-02', 'PRIVACY', 'EVIDENCE', 'fe-b-nf06-02.pdf'),
    (980415, 'NF06_SRE_LB', 'SRE-NF06-FIXTURE-03', 'SRE', 'EVIDENCE', 'fe-b-nf06-03.pdf'),
    (980416, 'NF06_DBA_ACCOUNT', 'DBA-NF06-FIXTURE-04', 'DBA', 'EVIDENCE', 'fe-b-nf06-04.pdf'),
    (980417, 'NF06_STAGING_CHECK', 'STG-NF06-FIXTURE-05', 'PRODUCTION_OWNER', 'EVIDENCE', 'fe-b-nf06-05.pdf')
) AS v(id, kind_code, reference, signer_role, kind_class, stored_name)
WHERE NOT EXISTS (SELECT 1 FROM t_feature_sign_off s WHERE s.id = v.id);
