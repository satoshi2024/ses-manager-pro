-- ============================================================
-- R3_SCALE_300 承認route追加シード（V135）
-- staffing.overallocation 用のrouteを追加する。
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

INSERT IGNORE INTO `m_approval_route` (`id`,`tenant_id`,`request_type`,`applicant_role_condition`,`organization_id`,`min_amount`,`max_amount`,`version_no`,`valid_from`,`valid_to`,`active_flag`,`created_by`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('61027','1','staffing.overallocation',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0');
INSERT IGNORE INTO `m_approval_route_step` (`id`,`route_id`,`step_no`,`parallel_group`,`approver_type`,`approver_value`,`sla_hours`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('62027','61027','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0');

SET FOREIGN_KEY_CHECKS = 1;
