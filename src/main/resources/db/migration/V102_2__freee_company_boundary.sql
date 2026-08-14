-- V102_2__freee_company_boundary.sql
-- HFP-01-002: freee接続の事業所境界と接続状態。
-- 採番の経緯: V103〜V108はS12〜S17の予約番号のため、既存のV66_1/V74_1/V79_1と同じ
-- V102系サブ番号（Flyway表記 V102.2）を採番した（SpecDispatchConsistencyTest参照）。
--   - t_freee_connection.connection_status: CONNECTED / REAUTH_REQUIRED を永続化（DISCONNECTEDは行なし、MISCONFIGUREDは設定から導出）
--   - t_freee_employee_link.freee_company_id: 事業所内employee IDであることをDB境界で表現
--     （接続companyが一意に確定できるlegacy行だけbackfill。NULLは「要再確認」）
--   - 旧 uk_freee_link_employee(employee_id単独) を uk_freee_link_company_employee(company_id, employee_id) へ置換
--     （同一employee IDを別companyへ登録可、同一company内では不可。engineer重複は既存 uk_freee_link_engineer で常に不可）
ALTER TABLE t_freee_connection
  ADD COLUMN connection_status VARCHAR(32) NOT NULL DEFAULT 'CONNECTED'
    COMMENT '接続状態: CONNECTED / REAUTH_REQUIRED。DISCONNECTEDはactive行なし、MISCONFIGUREDは設定・row内容から導出';

ALTER TABLE t_freee_employee_link
  ADD COLUMN freee_company_id BIGINT NULL
    COMMENT 'freee事業所ID。NULLは接続companyが確定できないlegacy行（要再確認）で、給与表示には使用しない';

-- 有効な接続rowのcompany_idが一意に確定できる場合だけbackfillする（複数companyがある場合はNULLのまま）
UPDATE t_freee_employee_link l
SET l.freee_company_id = (
        SELECT c.company_id FROM t_freee_connection c
        WHERE c.deleted_flag = 0 AND c.company_id IS NOT NULL
        ORDER BY c.id DESC LIMIT 1)
WHERE l.deleted_flag = 0 AND l.freee_company_id IS NULL
  AND (SELECT COUNT(*) FROM t_freee_connection c
       WHERE c.deleted_flag = 0 AND c.company_id IS NOT NULL) = 1;

-- 旧employee単独UNIQUEをcompany+employee複合UNIQUEへ置換
ALTER TABLE t_freee_employee_link DROP INDEX uk_freee_link_employee;
ALTER TABLE t_freee_employee_link
  ADD UNIQUE KEY uk_freee_link_company_employee (freee_company_id, freee_employee_id);
