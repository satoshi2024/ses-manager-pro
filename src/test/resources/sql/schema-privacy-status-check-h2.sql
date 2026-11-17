-- V201（NF07 F1 の状態・区分の列の CHECK）の H2 変種。値の集合は V201 と同じ（PrivacyStatusCheckConstraintTest が照合する）。
-- 共有H2（DB_CLOSE_DELAY=-1）で context ごとに再実行するため、制約は IF NOT EXISTS で1つずつ足す
-- （MySQL には ADD CONSTRAINT IF NOT EXISTS が無いため V201 をそのまま再実行できない）。
ALTER TABLE t_privacy_request ADD CONSTRAINT IF NOT EXISTS chk_privacy_request_status
  CHECK (status IN ('REQUESTED', 'IDENTITY_VERIFIED', 'SCOPED', 'PREVIEWED', 'APPROVAL_PENDING', 'APPROVED', 'CLAIMED',
                    'DISPATCH_AUTHORIZED', 'EXECUTING', 'CONFIRMED', 'BLOCKED', 'UNKNOWN', 'PARTIAL_FAILED', 'CANCELLED'));
ALTER TABLE t_privacy_request ADD CONSTRAINT IF NOT EXISTS chk_privacy_request_channel
  CHECK (channel IN ('EMAIL', 'POSTAL', 'WEB_FORM', 'PHONE', 'IN_PERSON', 'OTHER'));
ALTER TABLE t_privacy_request ADD CONSTRAINT IF NOT EXISTS chk_privacy_request_delivery_method
  CHECK (delivery_method IS NULL OR delivery_method IN ('EMAIL', 'POSTAL', 'IN_PERSON', 'SECURE_TRANSFER', 'OTHER'));
ALTER TABLE t_legal_hold ADD CONSTRAINT IF NOT EXISTS chk_legal_hold_kind CHECK (hold_kind IN ('UNKNOWN'));
