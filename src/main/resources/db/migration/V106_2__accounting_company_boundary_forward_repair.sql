-- V106.2: 会計connectionのcompany_id単位 forward repair
--
-- V106/V106.1は適用済み環境のFlyway checksumを守るため変更しない。
-- 旧V106.1が作成したlegal entity単位のUNIQUEを、G4の
-- legal entity × freee product × company_id単位へ順方向に修復する。
-- Flyway外のlegacy preflight退避行およびV106.1 backupのcompany別行もここで復元する。

DELIMITER $$

DROP PROCEDURE IF EXISTS __ses_accounting_company_boundary_forward_repair $$

CREATE PROCEDURE __ses_accounting_company_boundary_forward_repair()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'm_integration_connection'
          AND column_name = 'external_company_key'
    ) THEN
        ALTER TABLE m_integration_connection
            ADD COLUMN external_company_key BIGINT
                GENERATED ALWAYS AS (COALESCE(external_company_id, 0)) STORED
                COMMENT '事業所(company_id)一意性保証用生成列';
    END IF;

    -- 既存の旧/新いずれの定義でも同名indexを再構成する。
    IF EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'm_integration_connection'
          AND index_name = 'uk_int_conn'
    ) THEN
        ALTER TABLE m_integration_connection DROP INDEX uk_int_conn;
    END IF;

    -- 旧V106.1がcompany_idを無視して退避した行を、company単位で最大1行だけ復元する。
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'm_integration_connection_backup_v106_1'
    ) THEN
        DROP TEMPORARY TABLE IF EXISTS __ses_v106_2_restore_ids;
        CREATE TEMPORARY TABLE __ses_v106_2_restore_ids (
            original_id BIGINT PRIMARY KEY
        );

        INSERT INTO __ses_v106_2_restore_ids (original_id)
        SELECT original_id
        FROM (
            SELECT b.original_id,
                   ROW_NUMBER() OVER (
                       PARTITION BY b.tenant_id, COALESCE(b.legal_entity_id, 0),
                                    COALESCE(b.external_company_id, 0), b.provider, b.product
                       ORDER BY
                           CASE WHEN b.status = 'CONNECTED'
                                      AND b.encrypted_tokens IS NOT NULL
                                      AND b.expires_at > NOW() THEN 0 ELSE 1 END,
                           COALESCE(b.last_refreshed_at, '1970-01-01') DESC,
                           b.original_id DESC
                   ) AS rn
            FROM m_integration_connection_backup_v106_1 b
            WHERE b.deleted_flag = 0
              AND NOT EXISTS (
                  SELECT 1 FROM m_integration_connection c
                  WHERE c.deleted_flag = 0
                    AND c.tenant_id = b.tenant_id
                    AND COALESCE(c.legal_entity_id, 0) = COALESCE(b.legal_entity_id, 0)
                    AND COALESCE(c.external_company_id, 0) = COALESCE(b.external_company_id, 0)
                    AND c.provider = b.provider
                    AND c.product = b.product
              )
        ) ranked
        WHERE rn = 1;

        UPDATE m_integration_connection c
        INNER JOIN __ses_v106_2_restore_ids r ON r.original_id = c.id
        SET c.deleted_flag = 0, c.version = c.version + 1;

        DROP TEMPORARY TABLE __ses_v106_2_restore_ids;
    END IF;

    -- V106到達前にrunbookが退避した複数companyのlegacy行を新connectionとして復元する。
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'm_accounting_legacy_freee_preflight_v105_4'
    ) THEN
        INSERT INTO m_integration_connection (
            tenant_id, legal_entity_id, provider, product, external_company_id,
            company_name, encrypted_tokens, expires_at, status, connected_by,
            connected_at, last_refreshed_at, deleted_flag, version
        )
        SELECT 'default', NULL, 'freee', 'payroll', p.company_id,
               p.company_name,
               JSON_OBJECT('accessToken', p.access_token_encrypted,
                           'refreshToken', p.refresh_token_encrypted),
               p.token_expires_at,
               COALESCE(p.connection_status, 'CONNECTED'),
               p.connected_by, p.created_at, p.updated_at, 0, 0
        FROM m_accounting_legacy_freee_preflight_v105_4 p
        WHERE NOT EXISTS (
            SELECT 1 FROM m_integration_connection c
            WHERE c.deleted_flag = 0
              AND c.tenant_id = 'default'
              AND c.legal_entity_id IS NULL
              AND c.provider = 'freee'
              AND c.product = 'payroll'
              AND c.external_company_id <=> p.company_id
        );

        UPDATE t_freee_connection c
        INNER JOIN m_accounting_legacy_freee_preflight_v105_4 p ON p.source_id = c.id
        SET c.deleted_flag = p.deleted_flag;

        DROP TABLE m_accounting_legacy_freee_preflight_v105_4;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'm_integration_connection'
          AND index_name = 'uk_int_conn'
    ) THEN
        ALTER TABLE m_integration_connection
            ADD UNIQUE KEY uk_int_conn (
                tenant_id, legal_entity_key, external_company_key,
                provider, product, active_slot
            );
    END IF;
END $$

DELIMITER ;

CALL __ses_accounting_company_boundary_forward_repair();

DROP PROCEDURE IF EXISTS __ses_accounting_company_boundary_forward_repair;
