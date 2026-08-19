-- V105.4: S15 legacy freee connection preflight
--
-- V106/V106.1 は適用済み環境のchecksum契約により変更しない。
-- S15がまだ未適用の歴史的V105.3相当DBで、V106の旧UNIQUEへ複数companyを
-- 同時投入すると失敗するため、V106到達前だけ移行元を退避する。
-- V106.2 が退避行をcompany_id単位で復元し、処理成功後に退避表を削除する。

DELIMITER $$

DROP PROCEDURE IF EXISTS __ses_accounting_legacy_freee_preflight $$

CREATE PROCEDURE __ses_accounting_legacy_freee_preflight()
BEGIN
    -- consolidated V1 は既にS15表を持つため、fresh経路では何もしない。
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name = 'm_integration_connection'
    ) THEN
        CREATE TABLE IF NOT EXISTS m_accounting_legacy_freee_preflight_v105_4 (
            source_id BIGINT PRIMARY KEY,
            company_id BIGINT NULL,
            company_name VARCHAR(200) NULL,
            access_token_encrypted TEXT NOT NULL,
            refresh_token_encrypted TEXT NULL,
            token_expires_at DATETIME NULL,
            connected_by BIGINT NULL,
            created_at DATETIME NULL,
            updated_at DATETIME NULL,
            deleted_flag TINYINT NOT NULL DEFAULT 0,
            connection_status VARCHAR(32) NULL,
            preflighted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
          COMMENT='V106到達前に退避したlegacy freee connection';

        -- V106は全legacy freee connectionを同一のdefault/payroll接続へ写像するため、
        -- 旧UNIQUEに入る代表1行だけを残し、残りを退避する。
        INSERT IGNORE INTO m_accounting_legacy_freee_preflight_v105_4 (
            source_id, company_id, company_name, access_token_encrypted,
            refresh_token_encrypted, token_expires_at, connected_by,
            created_at, updated_at, deleted_flag, connection_status
        )
        SELECT c.id, c.company_id, c.company_name, c.access_token_encrypted,
               c.refresh_token_encrypted, c.token_expires_at, c.connected_by,
               c.created_at, c.updated_at, c.deleted_flag, c.connection_status
        FROM t_freee_connection c
        WHERE c.deleted_flag = 0
          AND c.id NOT IN (
              SELECT survivor_id
              FROM (
                  SELECT FIRST_VALUE(id) OVER (
                      ORDER BY
                          CASE WHEN connection_status = 'CONNECTED'
                                     AND access_token_encrypted IS NOT NULL
                                     AND token_expires_at > NOW() THEN 0 ELSE 1 END,
                          COALESCE(updated_at, created_at, '1970-01-01') DESC,
                          id DESC
                  ) AS survivor_id
                  FROM t_freee_connection
                  WHERE deleted_flag = 0
              ) survivors
          )
          AND EXISTS (
              SELECT 1 FROM t_freee_connection c2
              WHERE c2.deleted_flag = 0 AND c2.id <> c.id
          );

        UPDATE t_freee_connection c
        INNER JOIN m_accounting_legacy_freee_preflight_v105_4 p ON p.source_id = c.id
        SET c.deleted_flag = 1;
    END IF;
END $$

DELIMITER ;

CALL __ses_accounting_legacy_freee_preflight();

DROP PROCEDURE IF EXISTS __ses_accounting_legacy_freee_preflight;
