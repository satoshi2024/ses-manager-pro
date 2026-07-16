CREATE TABLE t_freee_connection (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, company_id BIGINT, company_name VARCHAR(200),
 access_token_encrypted TEXT NOT NULL, refresh_token_encrypted TEXT, token_expires_at DATETIME,
 connected_by BIGINT, created_at DATETIME DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 deleted_flag TINYINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE t_freee_employee_link (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, engineer_id BIGINT NOT NULL, freee_employee_id VARCHAR(100) NOT NULL,
 confirmed_at DATETIME, confirmed_by BIGINT, created_at DATETIME DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 deleted_flag TINYINT NOT NULL DEFAULT 0, UNIQUE KEY uk_freee_link_engineer(engineer_id), UNIQUE KEY uk_freee_link_employee(freee_employee_id),
 CONSTRAINT fk_freee_link_engineer FOREIGN KEY(engineer_id) REFERENCES t_engineer(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO m_menu(menu_key,menu_name,path_prefix,api_prefix,sort_order) VALUES('payroll','給与情報','/payroll','/api/payroll',90);
INSERT IGNORE INTO t_role_menu(role,menu_id) SELECT '管理者',id FROM m_menu WHERE menu_key='payroll';
INSERT IGNORE INTO t_role_menu(role,menu_id) SELECT 'HR',id FROM m_menu WHERE menu_key='payroll';
