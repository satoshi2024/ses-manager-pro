-- FE-C06（feature-enablement-console design §4.4・§8.5 の 6）: prod の完全な context（ProductionSecurityConfigurationTest）で、
-- NF07 保持・保全が DB で開いていて、その条件（決定ID束縛・privacy-admin・Journal・鍵）を満たさない状態から起動する。
-- 要る署名（D-01・D-09・NF07_BRIDGE と確認済みの組）は有効期間内（openUntil が未来）にし、起動時検査の validator が
-- 開いている capability として検査する（以前は prod で起動を止めた）。値は全て合成（参照は決定 ID の形だけで、D-09 の
-- 構造化された値を持たないため束縛は成り立たない）。class 専用の H2 へ context の初期化で1回だけ投入する（冪等）。
INSERT INTO t_feature_enablement_request (id, tenant_id, request_type, target_key, status, condition_fingerprint,
    condition_summary, requested_by, requested_at, expires_at, decided_by, decided_at, version)
SELECT 980001, 'default', 'OPEN', 'NF07_RETENTION', 'APPROVED',
    'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff', 'fixture', 980101,
    TIMESTAMP '2026-11-20 09:00:00', TIMESTAMP '2026-11-27 09:00:00', 980102, TIMESTAMP '2026-11-20 10:00:00', 1
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM t_feature_enablement_request WHERE id = 980001);

INSERT INTO t_feature_enablement_state (tenant_id, feature_key, state, version, open_request_id, opened_by, approved_by,
    opened_at, updated_at)
SELECT 'default', 'NF07_RETENTION', 'OPEN', 1, 980001, 980101, 980102, TIMESTAMP '2026-11-20 10:00:00',
    TIMESTAMP '2026-11-20 10:00:00'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM t_feature_enablement_state WHERE tenant_id = 'default'
    AND feature_key = 'NF07_RETENTION');

INSERT INTO t_feature_sign_off (id, tenant_id, kind_code, reference, signer_role, signed_on, expires_on, kind_class,
    content_sha256, size_bytes, stored_name, status, registered_by, registered_at, confirmed_by, confirmed_at)
SELECT 980201, 'default', 'NF07_D01', 'D01-NF07-FIXTURE-0001', 'PRIVACY_OWNER', DATE '2020-01-01', DATE '2099-12-31',
    'DECISION', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 10, 'fe-c06-nf07-d01.pdf', 'CONFIRMED',
    980101, TIMESTAMP '2026-11-01 10:00:00', 980102, TIMESTAMP '2026-11-01 11:00:00'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM t_feature_sign_off WHERE id = 980201);

INSERT INTO t_feature_sign_off (id, tenant_id, kind_code, reference, signer_role, signed_on, expires_on, kind_class,
    content_sha256, size_bytes, stored_name, status, registered_by, registered_at, confirmed_by, confirmed_at)
SELECT 980202, 'default', 'NF07_D09', 'D09-NF07-FIXTURE-0009', 'D09_SIGNER', DATE '2020-01-01', DATE '2099-12-31',
    'DECISION', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 10, 'fe-c06-nf07-d09.pdf', 'CONFIRMED',
    980101, TIMESTAMP '2026-11-01 10:00:00', 980102, TIMESTAMP '2026-11-01 11:00:00'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM t_feature_sign_off WHERE id = 980202);

INSERT INTO t_feature_sign_off (id, tenant_id, kind_code, reference, signer_role, signed_on, expires_on, kind_class,
    content_sha256, size_bytes, stored_name, status, registered_by, registered_at, confirmed_by, confirmed_at)
SELECT 980203, 'default', 'NF07_BRIDGE', 'BRIDGE-NF07-FIXTURE-0001', 'TECH_LEAD', DATE '2020-01-01', DATE '2099-12-31',
    'EVIDENCE', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 10, 'fe-c06-nf07-bridge.pdf',
    'CONFIRMED', 980101, TIMESTAMP '2026-11-01 10:00:00', 980102, TIMESTAMP '2026-11-01 11:00:00'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM t_feature_sign_off WHERE id = 980203);

INSERT INTO t_feature_sign_off_bundle (id, tenant_id, bundle_kind, status, registered_by, registered_at, confirmed_by,
    confirmed_at)
SELECT 980301, 'default', 'NF07_DECISION_BINDING', 'CONFIRMED', 980101, TIMESTAMP '2026-11-01 10:00:00', 980102,
    TIMESTAMP '2026-11-01 11:00:00'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM t_feature_sign_off_bundle WHERE id = 980301);

INSERT INTO t_feature_sign_off_bundle_member (bundle_id, tenant_id, kind_code, sign_off_id, carried_over)
SELECT 980301, 'default', 'NF07_D01', 980201, 0
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM t_feature_sign_off_bundle_member WHERE bundle_id = 980301
    AND kind_code = 'NF07_D01');

INSERT INTO t_feature_sign_off_bundle_member (bundle_id, tenant_id, kind_code, sign_off_id, carried_over)
SELECT 980301, 'default', 'NF07_D09', 980202, 0
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM t_feature_sign_off_bundle_member WHERE bundle_id = 980301
    AND kind_code = 'NF07_D09');
