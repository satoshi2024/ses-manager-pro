-- FeatureEnablementSnapshotHolderStartupTest 専用の seed（専用の H2 DB の初期化で流す。共有の testdb には入れない）。
-- NF04 を承認した申請・開放状態と、NF04 が要する5種の確認済みの署名（試験の目録の組の member 2種と組を含む）を入れ、
-- 起動の時点で snapshot が NF04 を開いた状態で読み込まれることを確かめる。
INSERT INTO t_feature_enablement_generation (tenant_id, generation, updated_at)
VALUES ('default', 3, TIMESTAMP '2026-11-20 10:00:00');
INSERT INTO t_feature_enablement_request (id, tenant_id, request_type, target_key, status, condition_fingerprint,
                                          condition_summary, requested_by, requested_at, expires_at, decided_by, decided_at)
VALUES (990001, 'default', 'OPEN', 'NF04', 'APPROVED', '0000000000000000000000000000000000000000000000000000000000000000',
        'seed', 990010, TIMESTAMP '2026-11-20 09:00:00', TIMESTAMP '2026-11-27 09:00:00', 990020,
        TIMESTAMP '2026-11-20 10:00:00');
INSERT INTO t_feature_enablement_state (tenant_id, feature_key, state, version, open_request_id, opened_by, approved_by,
                                        opened_at, updated_at)
VALUES ('default', 'NF04', 'OPEN', 1, 990001, 990010, 990020, TIMESTAMP '2026-11-20 10:00:00',
        TIMESTAMP '2026-11-20 10:00:00');
INSERT INTO t_feature_sign_off (id, tenant_id, kind_code, reference, signer_role, signed_on, expires_on, kind_class,
                                content_sha256, size_bytes, stored_name, status, registered_by, registered_at,
                                confirmed_by, confirmed_at)
VALUES (990101, 'default', 'NF04_BUSINESS_OWNER', 'DOC-1', 'BUSINESS_OWNER', DATE '2026-01-01', DATE '2099-12-31',
        'DECISION', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 10, 'seed990101.pdf', 'CONFIRMED',
        990010, TIMESTAMP '2026-11-01 10:00:00', 990020, TIMESTAMP '2026-11-01 11:00:00'),
       (990102, 'default', 'NF04_SRE', 'DOC-2', 'SRE', DATE '2026-01-01', DATE '2099-12-31',
        'EVIDENCE', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 10, 'seed990102.pdf', 'CONFIRMED',
        990010, TIMESTAMP '2026-11-01 10:00:00', 990020, TIMESTAMP '2026-11-01 11:00:00'),
       (990103, 'default', 'NF04_PRIVACY', 'DOC-3', 'PRIVACY', DATE '2026-01-01', DATE '2099-12-31',
        'EVIDENCE', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 10, 'seed990103.pdf', 'CONFIRMED',
        990010, TIMESTAMP '2026-11-01 10:00:00', 990020, TIMESTAMP '2026-11-01 11:00:00'),
       (990104, 'default', 'NF04_SECURITY', 'DOC-4', 'SECURITY', DATE '2026-01-01', DATE '2099-12-31',
        'DECISION', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 10, 'seed990104.pdf', 'CONFIRMED',
        990010, TIMESTAMP '2026-11-01 10:00:00', 990020, TIMESTAMP '2026-11-01 11:00:00'),
       (990105, 'default', 'NF04_DEVICE_ACCEPTANCE', 'DOC-5', 'ACCEPTANCE_OWNER', DATE '2026-01-01', DATE '2099-12-31',
        'EVIDENCE', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 10, 'seed990105.pdf', 'CONFIRMED',
        990010, TIMESTAMP '2026-11-01 10:00:00', 990020, TIMESTAMP '2026-11-01 11:00:00'),
       (990106, 'default', 'NF04_BUNDLE_A', 'DOC-6', 'BUSINESS_OWNER', DATE '2026-01-01', DATE '2099-12-31',
        'DECISION', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 10, 'seed990106.pdf', 'CONFIRMED',
        990010, TIMESTAMP '2026-11-01 10:00:00', 990020, TIMESTAMP '2026-11-01 11:00:00'),
       (990107, 'default', 'NF04_BUNDLE_B', 'DOC-7', 'SRE', DATE '2026-01-01', DATE '2099-12-31',
        'EVIDENCE', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 10, 'seed990107.pdf', 'CONFIRMED',
        990010, TIMESTAMP '2026-11-01 10:00:00', 990020, TIMESTAMP '2026-11-01 11:00:00');
INSERT INTO t_feature_sign_off_bundle (id, tenant_id, bundle_kind, status, registered_by, registered_at, confirmed_by,
                                       confirmed_at)
VALUES (990201, 'default', 'TEST_BUNDLE', 'CONFIRMED', 990010, TIMESTAMP '2026-11-01 10:00:00', 990020,
        TIMESTAMP '2026-11-01 11:00:00');
INSERT INTO t_feature_sign_off_bundle_member (bundle_id, tenant_id, kind_code, sign_off_id, carried_over)
VALUES (990201, 'default', 'NF04_BUNDLE_A', 990106, 0),
       (990201, 'default', 'NF04_BUNDLE_B', 990107, 0);
