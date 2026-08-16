-- ============================================================
-- V104_3: ポータル監査ログ（S13 external-customer-bp-portal / T086 B1）
-- spec: external-customer-bp-portal
--
-- R4.2: portalのdownload/検収/提出/口座変更を外部user/組織/IP/時刻で監査する。
-- 内部ApiAuditFilter（/api/**の内部chain限定）ではportal操作を捕捉できないため、
-- portal専用のappend-onlyログテーブルへPortalAuditServiceが記録する。
-- ============================================================

CREATE TABLE IF NOT EXISTS t_portal_access_log (
  id             BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  portal_user_id BIGINT       NOT NULL COMMENT 'portal user ID（論理削除後の監査参照に備えFKなし）',
  portal_org_id  BIGINT       NOT NULL COMMENT 'portal組織ID（FKなし）',
  email          VARCHAR(255) NOT NULL COMMENT 'portal user email',
  org_type       VARCHAR(20)  NOT NULL COMMENT '組織種別: CUSTOMER / BP',
  action         VARCHAR(50)  NOT NULL COMMENT '操作（DOWNLOAD_QUOTATION / ACCEPT / REJECT / SUBMIT / CONFIRM_RECEIPT / BANK_REQUEST 等）',
  target_type    VARCHAR(50)  COMMENT '対象種別（QUOTATION / SALES_ORDER / CONTRACT / ACCEPTANCE / INVOICE / BP_PAYMENT 等）',
  target_id      BIGINT       COMMENT '対象ID',
  ip_hash        VARCHAR(64)  COMMENT '接続元IPのSHA-256 hash（平文IPは保存しない）',
  user_agent     VARCHAR(512) COMMENT 'User-Agent',
  created_at     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '記録日時',
  INDEX idx_portal_access_log_org (portal_org_id, created_at),
  INDEX idx_portal_access_log_user (portal_user_id, created_at),
  INDEX idx_portal_access_log_action (action, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ポータル操作監査ログ（append-only）';
