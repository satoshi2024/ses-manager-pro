-- V181: 月次締めを tenant 単位の正本テーブルへ移す（NF02 P0）。
-- 旧グローバル JSON（closing.confirmed-months）は default tenant のみへ安全に写経する。
-- 全 tenant への broadcast は禁止。V180 は BP 修正枠のため欠番。

CREATE TABLE t_monthly_closing (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id     VARCHAR(100) NULL COMMENT 'テナント境界（NULL行は不可視・書込禁止）',
    work_month    VARCHAR(7)   NOT NULL COMMENT '対象月(YYYY-MM)',
    confirmed_by  BIGINT       NULL COMMENT '締め実行者 user id',
    confirmed_at  DATETIME     NULL COMMENT '締め日時（NULL=未締め）',
    version       INT          NOT NULL DEFAULT 0 COMMENT 'CAS用バージョン',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_monthly_closing_tenant_month (tenant_id, work_month),
    KEY idx_monthly_closing_tenant_confirmed (tenant_id, confirmed_at, work_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='月次締め状態（tenant×月）';

-- 旧 JSON 配列を default tenant のみへ写経。破損・非配列はスキップ。
INSERT INTO t_monthly_closing (tenant_id, work_month, confirmed_by, confirmed_at, version)
SELECT
    'default' AS tenant_id,
    jt.work_month,
    COALESCE(jt.by_user, jt.user_id) AS confirmed_by,
    CAST(REPLACE(REPLACE(COALESCE(jt.at_ts, jt.confirmed_at), 'T', ' '), 'Z', '') AS DATETIME) AS confirmed_at,
    0 AS version
FROM m_system_config c
JOIN JSON_TABLE(
    CASE
        WHEN c.config_value IS NOT NULL
         AND JSON_VALID(c.config_value)
         AND JSON_TYPE(c.config_value) = 'ARRAY'
        THEN c.config_value
        ELSE '[]'
    END,
    '$[*]' COLUMNS (
        work_month   VARCHAR(7)  PATH '$.month',
        by_user      BIGINT      PATH '$.by',
        user_id      BIGINT      PATH '$.userId',
        at_ts        VARCHAR(40) PATH '$.at',
        confirmed_at VARCHAR(40) PATH '$.confirmedAt'
    )
) AS jt
WHERE c.config_key = 'closing.confirmed-months'
  AND jt.work_month IS NOT NULL
  AND jt.work_month <> ''
ON DUPLICATE KEY UPDATE tenant_id = tenant_id;

-- 旧キーは参照しないが、画面からの誤編集を防ぐため system-managed legacy として残す。
UPDATE m_system_config
SET description = '【移行済・参照禁止】旧グローバル月次締めJSON（V181で t_monthly_closing へ写経）'
WHERE config_key = 'closing.confirmed-months';
