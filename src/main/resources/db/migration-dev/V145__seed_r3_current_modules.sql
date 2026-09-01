INSERT IGNORE INTO `m_approval_route` (`id`,`tenant_id`,`request_type`,`applicant_role_condition`,`organization_id`,`min_amount`,`max_amount`,`version_no`,`valid_from`,`valid_to`,`active_flag`,`created_by`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('61001','1','quotation.submit',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61002','1','quotation.accept',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61003','1','quotation.status',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61004','1','contract.activate',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61005','1','contract.revisePrice',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61006','1','contract.status',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61007','1','invoice.send',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61008','1','invoice.void',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61009','1','invoice.status',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61010','1','bp_payment.confirm',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61011','1','closing.confirm',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61012','1','closing.reopen',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61013','1','attendance.reopen',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61014','1','leave.request',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61015','1','leave.cancel',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61016','1','acceptance.cancel',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61017','1','order.cancel',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61018','1','order.conditionDiff',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61019','1','profile.change',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61020','1','skill.change',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61021','1','career.change',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61022','1','expense.request',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61023','1','LIFECYCLE_EXCEPTION',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61024','1','lifecycle.exception',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61025','1','lifecycle.waive',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61026','1','bp_bank_account.change',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0');
INSERT IGNORE INTO `m_approval_route_step` (`id`,`route_id`,`step_no`,`parallel_group`,`approver_type`,`approver_value`,`sla_hours`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('62001','61001','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62002','61002','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62003','61003','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62004','61004','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62005','61005','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62006','61006','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62007','61007','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62008','61008','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62009','61009','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62010','61010','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62011','61011','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62012','61012','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62013','61013','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62014','61014','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62015','61015','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62016','61016','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62017','61017','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62018','61018','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62019','61019','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62020','61020','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62021','61021','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62022','61022','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62023','61023','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62024','61024','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62025','61025','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62026','61026','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0');
INSERT IGNORE INTO `t_approval_responsibility` (`id`,`tenant_id`,`responsibility_type`,`organization_id`,`user_id`,`valid_from`,`valid_to`,`active_flag`,`created_by`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('63001','1','FINANCE_MANAGER',NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('63002','1','ORGANIZATION_MANAGER','3005','135','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0');
INSERT IGNORE INTO `t_approval_delegation` (`id`,`from_user_id`,`to_user_id`,`valid_from`,`valid_to`,`request_types_json`,`reason`,`approved_by`,`created_by`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('64001','1','101','2026-01-01','2026-03-31','["quotation.submit","invoice.send"]','年度開始時の承認体制引継ぎ','1','1','2026-01-01 09:00:00','2026-01-01 09:00:00','0');
INSERT IGNORE INTO `t_approval_delegation_type` (`delegation_id`,`request_type`) VALUES
('64001','quotation.submit'),
('64001','invoice.send');
INSERT IGNORE INTO `t_approval_request` (`id`,`request_no`,`request_type`,`target_type`,`target_id`,`target_version`,`applicant_id`,`organization_id`,`amount_snapshot`,`payload_json`,`diff_json`,`route_snapshot_json`,`status`,`current_step`,`round_no`,`current_step_started_at`,`requested_at`,`finalized_at`,`idempotency_key`,`version`,`created_by`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('65001','AR-SEED-0001','quotation.submit','QUOTATION','15001','1','102','3002','750000','{"quotationId":15001,"status":"提出済"}','{"status":{"label":"見積状態","before":"下書き","after":"提出済"}}','{"routeId":61001,"versionNo":1,"organizationId":null,"steps":[{"stepNo":1,"slaHours":48,"approverUserIds":[1,101],"slots":[{"slotIndex":0,"approverType":"ROLE","candidateUserIds":[1,101],"requiredCount":1}]}]}','in_review','1','1','2026-08-10 10:00:00','2026-08-10 10:00:00',NULL,NULL,'1','102','2026-08-10 10:00:00','2026-08-10 10:00:00','0'),
('65002','AR-SEED-0002','invoice.send','INVOICE','10001','0','103','3003','8856100','{"invoiceId":10001,"status":"送付済"}','{"status":{"label":"請求書状態","before":"下書き","after":"送付済"}}','{"routeId":61007,"versionNo":1,"organizationId":null,"steps":[{"stepNo":1,"slaHours":48,"approverUserIds":[1,101],"slots":[{"slotIndex":0,"approverType":"ROLE","candidateUserIds":[1,101],"requiredCount":1}]}]}','approved','1','1','2026-08-08 10:00:00','2026-08-08 10:00:00','2026-08-08 10:05:00',NULL,'1','103','2026-08-08 10:00:00','2026-08-08 10:05:00','0'),
('65003','AR-SEED-0003','profile.change','CHANGE_REQUEST','84001','0','397','3005',NULL,'{"fullName":"田中 太郎"}','{"fullName":{"label":"氏名","before":"田中 太郎","after":"田中 太郎"}}','{"routeId":61019,"versionNo":1,"organizationId":null,"steps":[{"stepNo":1,"slaHours":48,"approverUserIds":[1,101],"slots":[{"slotIndex":0,"approverType":"ROLE","candidateUserIds":[1,101],"requiredCount":1}]}]}','in_review','1','1','2026-08-11 09:00:00','2026-08-11 09:00:00',NULL,NULL,'1','397','2026-08-11 09:00:00','2026-08-11 09:00:00','0'),
('65004','AR-SEED-0004','expense.request','EXPENSE_REQUEST','84101','0','397','3005','18000','{"expenseNo":"EX-SEED-0001","category":"交通費","amount":18000}','{"status":{"label":"経費状態","before":"下書き","after":"申請中"}}','{"routeId":61022,"versionNo":1,"organizationId":null,"steps":[{"stepNo":1,"slaHours":48,"approverUserIds":[1,101],"slots":[{"slotIndex":0,"approverType":"ROLE","candidateUserIds":[1,101],"requiredCount":1}]}]}','in_review','1','1','2026-08-12 09:00:00','2026-08-12 09:00:00',NULL,NULL,'1','397','2026-08-12 09:00:00','2026-08-12 09:00:00','0'),
('65005','AR-SEED-0005','LIFECYCLE_EXCEPTION','LIFECYCLE_TASK','86021','0','135','3004','0','{"taskId":86021,"reason":"返却証跡の再取得中","riskOwner":"管理部","remedyDeadline":"2026-09-30"}','{"status":{"label":"タスク状態","before":"PENDING","after":"WAIVED"}}','{"routeId":61023,"versionNo":1,"organizationId":null,"steps":[{"stepNo":1,"slaHours":48,"approverUserIds":[1,101],"slots":[{"slotIndex":0,"approverType":"ROLE","candidateUserIds":[1,101],"requiredCount":1}]}]}','approved','1','1','2026-08-07 14:00:00','2026-08-07 14:00:00','2026-08-07 14:05:00',NULL,'1','135','2026-08-07 14:00:00','2026-08-07 14:05:00','0');
INSERT IGNORE INTO `t_approval_participant` (`id`,`request_id`,`user_id`,`participant_role`,`round_no`) VALUES
('65001','65001','102','applicant','1'),
('65002','65001','1','approver','1'),
('65003','65001','101','approver','1'),
('65004','65002','103','applicant','1'),
('65005','65002','1','approver','1'),
('65006','65002','101','approver','1'),
('65007','65003','397','applicant','1'),
('65008','65003','1','approver','1'),
('65009','65003','101','approver','1'),
('65010','65004','397','applicant','1'),
('65011','65004','1','approver','1'),
('65012','65004','101','approver','1'),
('65013','65005','135','applicant','1'),
('65014','65005','1','approver','1'),
('65015','65005','101','approver','1');
INSERT IGNORE INTO `t_approval_action` (`id`,`request_id`,`round_no`,`step_no`,`slot_index`,`approver_user_id`,`approver_slot_user_id`,`action`,`comment`,`delegated_from`,`acted_at`) VALUES
('66001','65002','1','1','0','1','1','APPROVE','請求内容を確認済み',NULL,'2026-08-08 10:05:00'),
('66002','65005','1','1','0','1','1','APPROVE','例外免除理由と是正期限を確認済み',NULL,'2026-08-07 14:05:00');
-- ============================================================
-- R3_SCALE_300 現行モジュール補助シード（V101以降）
-- 承認route、要員セルフサービス、ポータル、会計連携、
-- ライフサイクル、レポート、資格研修、資産管理の代表データ
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

INSERT IGNORE INTO `m_approval_route` (`id`,`tenant_id`,`request_type`,`applicant_role_condition`,`organization_id`,`min_amount`,`max_amount`,`version_no`,`valid_from`,`valid_to`,`active_flag`,`created_by`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('61001','1','quotation.submit',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61002','1','quotation.accept',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61003','1','quotation.status',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61004','1','contract.activate',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61005','1','contract.revisePrice',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61006','1','contract.status',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61007','1','invoice.send',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61008','1','invoice.void',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61009','1','invoice.status',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61010','1','bp_payment.confirm',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61011','1','closing.confirm',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61012','1','closing.reopen',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61013','1','attendance.reopen',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61014','1','leave.request',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61015','1','leave.cancel',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61016','1','acceptance.cancel',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61017','1','order.cancel',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61018','1','order.conditionDiff',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61019','1','profile.change',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61020','1','skill.change',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61021','1','career.change',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61022','1','expense.request',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61023','1','LIFECYCLE_EXCEPTION',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61024','1','lifecycle.exception',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61025','1','lifecycle.waive',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('61026','1','bp_bank_account.change',NULL,NULL,NULL,NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0');
INSERT IGNORE INTO `m_approval_route_step` (`id`,`route_id`,`step_no`,`parallel_group`,`approver_type`,`approver_value`,`sla_hours`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('62001','61001','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62002','61002','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62003','61003','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62004','61004','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62005','61005','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62006','61006','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62007','61007','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62008','61008','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62009','61009','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62010','61010','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62011','61011','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62012','61012','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62013','61013','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62014','61014','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62015','61015','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62016','61016','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62017','61017','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62018','61018','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62019','61019','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62020','61020','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62021','61021','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62022','61022','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62023','61023','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62024','61024','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62025','61025','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('62026','61026','1','1','ROLE','管理者','48','2026-08-09 09:00:00','2026-08-09 09:00:00','0');
INSERT IGNORE INTO `t_approval_responsibility` (`id`,`tenant_id`,`responsibility_type`,`organization_id`,`user_id`,`valid_from`,`valid_to`,`active_flag`,`created_by`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('63001','1','FINANCE_MANAGER',NULL,'1','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('63002','1','ORGANIZATION_MANAGER','3005','135','2024-04-01',NULL,'1','1','2026-08-09 09:00:00','2026-08-09 09:00:00','0');
INSERT IGNORE INTO `t_approval_delegation` (`id`,`from_user_id`,`to_user_id`,`valid_from`,`valid_to`,`request_types_json`,`reason`,`approved_by`,`created_by`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('64001','1','101','2026-01-01','2026-03-31','["quotation.submit","invoice.send"]','年度開始時の承認体制引継ぎ','1','1','2026-01-01 09:00:00','2026-01-01 09:00:00','0');
INSERT IGNORE INTO `t_approval_delegation_type` (`delegation_id`,`request_type`) VALUES
('64001','quotation.submit'),
('64001','invoice.send');
INSERT IGNORE INTO `t_approval_request` (`id`,`request_no`,`request_type`,`target_type`,`target_id`,`target_version`,`applicant_id`,`organization_id`,`amount_snapshot`,`payload_json`,`diff_json`,`route_snapshot_json`,`status`,`current_step`,`round_no`,`current_step_started_at`,`requested_at`,`finalized_at`,`idempotency_key`,`version`,`created_by`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('65001','AR-SEED-0001','quotation.submit','QUOTATION','15001','1','102','3002','750000','{"quotationId":15001,"status":"提出済"}','{"status":{"label":"見積状態","before":"下書き","after":"提出済"}}','{"routeId":61001,"versionNo":1,"organizationId":null,"steps":[{"stepNo":1,"slaHours":48,"approverUserIds":[1,101],"slots":[{"slotIndex":0,"approverType":"ROLE","candidateUserIds":[1,101],"requiredCount":1}]}]}','in_review','1','1','2026-08-10 10:00:00','2026-08-10 10:00:00',NULL,NULL,'1','102','2026-08-10 10:00:00','2026-08-10 10:00:00','0'),
('65002','AR-SEED-0002','invoice.send','INVOICE','10001','0','103','3003','8856100','{"invoiceId":10001,"status":"送付済"}','{"status":{"label":"請求書状態","before":"下書き","after":"送付済"}}','{"routeId":61007,"versionNo":1,"organizationId":null,"steps":[{"stepNo":1,"slaHours":48,"approverUserIds":[1,101],"slots":[{"slotIndex":0,"approverType":"ROLE","candidateUserIds":[1,101],"requiredCount":1}]}]}','approved','1','1','2026-08-08 10:00:00','2026-08-08 10:00:00','2026-08-08 10:05:00',NULL,'1','103','2026-08-08 10:00:00','2026-08-08 10:05:00','0'),
('65003','AR-SEED-0003','profile.change','CHANGE_REQUEST','84001','0','397','3005',NULL,'{"fullName":"田中 太郎"}','{"fullName":{"label":"氏名","before":"田中 太郎","after":"田中 太郎"}}','{"routeId":61019,"versionNo":1,"organizationId":null,"steps":[{"stepNo":1,"slaHours":48,"approverUserIds":[1,101],"slots":[{"slotIndex":0,"approverType":"ROLE","candidateUserIds":[1,101],"requiredCount":1}]}]}','in_review','1','1','2026-08-11 09:00:00','2026-08-11 09:00:00',NULL,NULL,'1','397','2026-08-11 09:00:00','2026-08-11 09:00:00','0'),
('65004','AR-SEED-0004','expense.request','EXPENSE_REQUEST','84101','0','397','3005','18000','{"expenseNo":"EX-SEED-0001","category":"交通費","amount":18000}','{"status":{"label":"経費状態","before":"下書き","after":"申請中"}}','{"routeId":61022,"versionNo":1,"organizationId":null,"steps":[{"stepNo":1,"slaHours":48,"approverUserIds":[1,101],"slots":[{"slotIndex":0,"approverType":"ROLE","candidateUserIds":[1,101],"requiredCount":1}]}]}','in_review','1','1','2026-08-12 09:00:00','2026-08-12 09:00:00',NULL,NULL,'1','397','2026-08-12 09:00:00','2026-08-12 09:00:00','0'),
('65005','AR-SEED-0005','LIFECYCLE_EXCEPTION','LIFECYCLE_TASK','86021','0','135','3004','0','{"taskId":86021,"reason":"返却証跡の再取得中","riskOwner":"管理部","remedyDeadline":"2026-09-30"}','{"status":{"label":"タスク状態","before":"PENDING","after":"WAIVED"}}','{"routeId":61023,"versionNo":1,"organizationId":null,"steps":[{"stepNo":1,"slaHours":48,"approverUserIds":[1,101],"slots":[{"slotIndex":0,"approverType":"ROLE","candidateUserIds":[1,101],"requiredCount":1}]}]}','approved','1','1','2026-08-07 14:00:00','2026-08-07 14:00:00','2026-08-07 14:05:00',NULL,'1','135','2026-08-07 14:00:00','2026-08-07 14:05:00','0');
INSERT IGNORE INTO `t_approval_participant` (`id`,`request_id`,`user_id`,`participant_role`,`round_no`) VALUES
('65001','65001','102','applicant','1'),
('65002','65001','1','approver','1'),
('65003','65001','101','approver','1'),
('65004','65002','103','applicant','1'),
('65005','65002','1','approver','1'),
('65006','65002','101','approver','1'),
('65007','65003','397','applicant','1'),
('65008','65003','1','approver','1'),
('65009','65003','101','approver','1'),
('65010','65004','397','applicant','1'),
('65011','65004','1','approver','1'),
('65012','65004','101','approver','1'),
('65013','65005','135','applicant','1'),
('65014','65005','1','approver','1'),
('65015','65005','101','approver','1');
INSERT IGNORE INTO `t_approval_action` (`id`,`request_id`,`round_no`,`step_no`,`slot_index`,`approver_user_id`,`approver_slot_user_id`,`action`,`comment`,`delegated_from`,`acted_at`) VALUES
('66001','65002','1','1','0','1','1','APPROVE','請求内容を確認済み',NULL,'2026-08-08 10:05:00'),
('66002','65005','1','1','0','1','1','APPROVE','例外免除理由と是正期限を確認済み',NULL,'2026-08-07 14:05:00');
INSERT IGNORE INTO `t_project_position` (`id`,`project_id`,`position_no`,`role_name`,`required_count`,`skills_json`,`unit_price_min`,`unit_price_max`,`start_date`,`end_date`,`location`,`allocation_percent`,`priority`,`status`,`version`,`created_by`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('80001','5001','POS-2026-001','Javaバックエンドエンジニア','2','[{"skillId":1,"level":"上級","required":true},{"skillId":14,"level":"中級","required":true}]','650000','850000','2026-09-01','2027-03-31','東京都千代田区','100','急募','募集中','0','101','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('80002','5002','POS-2026-002','Reactフロントエンドエンジニア','1','[{"skillId":4,"level":"上級","required":true},{"skillId":15,"level":"中級","required":true}]','600000','780000','2026-10-01','2027-06-30','大阪府大阪市','80','通常','候補選定','0','102','2026-08-09 09:00:00','2026-08-09 09:00:00','0');
INSERT IGNORE INTO `t_allocation_plan` (`id`,`engineer_id`,`position_id`,`allocation_type`,`start_date`,`end_date`,`allocation_percent`,`status`,`source_contract_id`,`exception_reason`,`approval_request_id`,`version`,`created_by`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('81001','1','80001','案件','2026-08-01','2026-08-31','100','確定','7001',NULL,NULL,'0','101','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('81002','1001','80001','案件','2026-09-01','2027-03-31','100','下書き',NULL,NULL,NULL,'0','102','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('81003','3',NULL,'待機','2026-08-01',NULL,'100','確定',NULL,NULL,NULL,'0','101','2026-08-09 09:00:00','2026-08-09 09:00:00','0');
INSERT IGNORE INTO `t_staffing_scenario` (`id`,`owner_user_id`,`name`,`base_date`,`shared_flag`,`assumptions_json`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('82001','101','2026年秋案件配置案','2026-08-01','1','{"note":"秋口の新規案件を前提にした仮配置","utilizationTarget":85}','2026-08-09 09:00:00','2026-08-09 09:00:00','0');
INSERT IGNORE INTO `t_staffing_scenario_allocation` (`id`,`scenario_id`,`engineer_id`,`position_id`,`dates`,`percent`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('82011','82001','1001','80001','["2026-09-01","2026-10-01","2026-11-01"]','100','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('82012','82001','1002','80002','["2026-10-01","2026-11-01","2026-12-01"]','80','2026-08-09 09:00:00','2026-08-09 09:00:00','0');
INSERT IGNORE INTO `m_portal_organization` (`id`,`tenant_id`,`type`,`customer_id`,`bp_company_id`,`status`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('83001','default','CUSTOMER','2001',NULL,'ACTIVE','2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('83002','default','BP',NULL,'11001','ACTIVE','2026-08-09 09:00:00','2026-08-09 09:00:00','0');
INSERT IGNORE INTO `t_portal_user` (`id`,`portal_org_id`,`email`,`display_name`,`password_hash`,`status`,`mfa_policy`,`notify_email`,`totp_secret_encrypted`,`totp_secret_key_version`,`mfa_enabled_at`,`recovery_code_hash`,`recovery_code_used_at`,`last_used_step`,`last_login_at`,`version`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('83101','83001','portal.customer01@example.jp','顧客ポータル担当',NULL,'ACTIVE','OPTIONAL','1',NULL,NULL,NULL,NULL,NULL,NULL,'2026-08-08 10:00:00','0','2026-08-01 09:00:00','2026-08-08 10:00:00','0'),
('83102','83002','portal.bp01@example.jp','BPポータル担当',NULL,'ACTIVE','OPTIONAL','1',NULL,NULL,NULL,NULL,NULL,NULL,'2026-08-07 15:00:00','0','2026-08-01 09:00:00','2026-08-07 15:00:00','0');
INSERT IGNORE INTO `t_portal_user_permission` (`id`,`user_id`,`permission_key`,`created_at`) VALUES
('83201','83101','document.view','2026-08-01 09:00:00'),
('83202','83101','invoice.view','2026-08-01 09:00:00'),
('83203','83101','acceptance.operate','2026-08-01 09:00:00'),
('83204','83102','availability.view','2026-08-01 09:00:00'),
('83205','83102','bank-account.request','2026-08-01 09:00:00');
INSERT IGNORE INTO `t_portal_invitation` (`id`,`portal_org_id`,`email`,`role`,`token_hash`,`expires_at`,`used_at`,`accepted_by`,`invited_by`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('83301','83002','portal.bp02@example.jp','MEMBER','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','2026-09-01 23:59:00',NULL,NULL,'101','2026-08-09 09:00:00','2026-08-09 09:00:00','0');
INSERT IGNORE INTO `t_portal_terms_consent` (`id`,`user_id`,`terms_version`,`consented_at`,`ip_hash`,`created_at`) VALUES
('83251','83101','2026.1','2026-08-01 09:05:00','bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb','2026-08-01 09:05:00');
INSERT IGNORE INTO `t_portal_session` (`id`,`user_id`,`token_hash`,`issued_at`,`last_seen_at`,`idle_expires_at`,`expires_at`,`ip_hash`,`user_agent`,`revoked_at`,`revoked_reason`,`created_at`) VALUES
('83401','83101','cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc','2026-08-08 10:00:00','2026-08-08 11:00:00','2026-08-08 22:00:00','2026-08-09 10:00:00','dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd','Mozilla/5.0 (seed)',NULL,NULL,'2026-08-08 10:00:00');
INSERT IGNORE INTO `t_portal_access_log` (`id`,`portal_user_id`,`portal_org_id`,`email`,`org_type`,`action`,`target_type`,`target_id`,`ip_hash`,`user_agent`,`created_at`) VALUES
('83501','83101','83001','portal.customer01@example.jp','CUSTOMER','DOWNLOAD_QUOTATION','QUOTATION','15001','eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee','Mozilla/5.0 (seed)','2026-08-08 10:30:00');
INSERT IGNORE INTO `t_engineer_change_request` (`id`,`engineer_id`,`request_type`,`payload_json`,`diff_json`,`reason`,`attachment_document_id`,`status`,`approval_request_id`,`applied_at`,`version`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('84001','1','profile.change','{"fullName":"田中 太郎","phone":"090-0000-0001"}','{"fullName":{"label":"氏名","before":"田中 太郎","after":"田中 太郎"},"phone":{"label":"電話番号","before":null,"after":"090-0000-0001"}}','連絡先を最新情報へ更新',NULL,'申請中','65003',NULL,'0','2026-08-11 09:00:00','2026-08-11 09:00:00','0'),
('84002','1002','skill.change','{"skills":[{"skillId":4,"proficiency":"上級","experienceYears":4},{"skillId":15,"proficiency":"上級","experienceYears":3}]}','{"skills":{"label":"保有スキル","before":"TypeScript/React","after":"TypeScript/React（更新）"}}','スキルシート更新の下書き',NULL,'下書き',NULL,NULL,'0','2026-08-12 09:00:00','2026-08-12 09:00:00','0');
INSERT IGNORE INTO `t_expense_request` (`id`,`engineer_id`,`expense_no`,`expense_date`,`category`,`amount`,`customer_id`,`project_id`,`description`,`receipt_document_id`,`status`,`approval_request_id`,`accounting_job_id`,`paid_at`,`version`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('84101','1','EX-SEED-0001','2026-08-05','交通費','18000','2001','5001','顧客訪問の往復交通費',NULL,'申請中','65004',NULL,NULL,'0','2026-08-12 09:00:00','2026-08-12 09:00:00','0'),
('84102','1001','EX-SEED-0002','2026-07-20','研修費','55000',NULL,NULL,'AWS認定対策講座',NULL,'支払済',NULL,'84111','2026-08-05','1','2026-07-21 09:00:00','2026-08-05 10:00:00','0');
INSERT IGNORE INTO `t_expense_accounting_job` (`id`,`expense_request_id`,`status`,`correlation_id`,`payload_hash`,`attempt_count`,`next_attempt_at`,`last_error_code`,`sent_at`,`created_at`,`updated_at`) VALUES
('84111','84102','SUCCEEDED','expense-seed-84102','ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff','1',NULL,NULL,'2026-08-05 10:00:00','2026-08-05 09:00:00','2026-08-05 10:00:00');
INSERT IGNORE INTO `t_one_on_one_request` (`id`,`engineer_id`,`counterpart_user_id`,`candidate_dates_json`,`scheduled_at`,`status`,`employee_visible_note`,`private_note_ref`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('84201','1001','135','["2026-08-20","2026-08-22"]','2026-08-20','日程確定','次回案件とキャリア希望を確認予定',NULL,'2026-08-09 09:00:00','2026-08-09 09:00:00','0'),
('84202','1002','135','["2026-07-10"]','2026-07-10','実施済','稼働状況は良好です。','PRIVATE-84202','2026-07-01 09:00:00','2026-07-10 17:00:00','0');
INSERT IGNORE INTO `m_survey_template` (`id`,`template_key`,`title`,`description`,`questions_json`,`status`,`version`,`created_by`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('84301','monthly-pulse','月次コンディションサーベイ','要員の稼働状況と困りごとを確認する月次アンケート','{"questions":[{"key":"satisfaction","text":"現在の満足度","type":"scale","confidential":false},{"key":"concern","text":"相談事項","type":"text","confidential":true}]}','ACTIVE','1','127','2026-08-01 09:00:00','2026-08-01 09:00:00','0');
INSERT IGNORE INTO `t_survey_campaign` (`id`,`template_id`,`title`,`template_snapshot_json`,`template_snapshot_version`,`period_from`,`period_to`,`status`,`created_by`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('84311','84301','2026年8月 月次サーベイ','{"version":1,"questions":["satisfaction","concern"]}','1','2026-08-01','2026-08-31','ACTIVE','127','2026-08-01 09:00:00','2026-08-01 09:00:00','0');
INSERT IGNORE INTO `t_survey_response` (`id`,`campaign_id`,`engineer_id`,`question_key`,`answer_value`,`comment`,`comment_visibility`,`consent_flag`,`template_version`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('84321','84311','1001','satisfaction','4','現場との連携は順調です。','PUBLIC','1','1','2026-08-08 18:00:00','2026-08-08 18:00:00','0'),
('84322','84311','1001','concern',NULL,'次の案件では設計工程にも参加したいです。','CONFIDENTIAL','1','1','2026-08-08 18:01:00','2026-08-08 18:01:00','0');
INSERT IGNORE INTO `m_external_mapping` (`id`,`connection_id`,`object_type`,`internal_id`,`internal_code`,`external_id`,`external_code`,`payload_snapshot`,`verified_at`,`created_at`,`updated_at`,`deleted_flag`,`version`) VALUES
('85001','1','CUSTOMER','2001','CUST-2001','freee-customer-2001','顧客001','{"source":"seed","synced":true}','2026-08-08 10:00:00','2026-08-08 10:00:00','2026-08-08 10:00:00','0','1');
INSERT IGNORE INTO `t_integration_job` (`id`,`connection_id`,`job_type`,`target_type`,`target_id`,`tenant_id`,`legal_entity_id`,`organization_id`,`idempotency_key`,`payload_snapshot`,`payload_hash`,`status`,`lease_token`,`lease_expires_at`,`attempt_count`,`max_attempts`,`next_retry_at`,`external_id`,`provider_request_id`,`error_code`,`error_message_safe`,`sent_at`,`created_at`,`updated_at`,`deleted_flag`,`version`) VALUES
('85011','1','INVOICE_EXPORT','INVOICE','10001','default',NULL,'3001','invoice-export-10001','{"invoiceId":10001,"format":"freee"}','1111111111111111111111111111111111111111111111111111111111111111','SUCCEEDED',NULL,NULL,'1','5',NULL,'freee-invoice-10001','freee-req-10001',NULL,NULL,'2026-08-08 10:05:00','2026-08-08 10:00:00','2026-08-08 10:05:00','0','0');
INSERT IGNORE INTO `t_integration_job_event` (`id`,`job_id`,`from_status`,`to_status`,`occurred_at`,`safe_detail`) VALUES
('85021','85011','PENDING','SUCCEEDED','2026-08-08 10:05:00','freee送信完了（seed）');
INSERT IGNORE INTO `t_peppol_participant` (`id`,`owner_type`,`owner_id`,`scheme_id`,`participant_id`,`provider`,`status`,`verified_at`,`created_at`,`created_by`,`updated_at`,`updated_by`,`deleted_flag`) VALUES
('85031','CUSTOMER','2001','0088','0088:seed-customer-2001','mock','VERIFIED','2026-08-01 09:00:00','2026-08-01 09:00:00','s300.admin01','2026-08-01 09:00:00','s300.admin01','0');
INSERT IGNORE INTO `t_digital_invoice` (`id`,`invoice_id`,`direction`,`profile`,`specification_version`,`message_id`,`provider_message_id`,`xml_document_id`,`validation_document_id`,`status`,`sent_at`,`received_at`,`version`,`created_at`,`created_by`,`updated_at`,`updated_by`,`deleted_flag`,`supplier_company_id`,`purchase_order_id`,`contract_id`,`match_status`) VALUES
('85041','10001','SEND','JP_PINT','1.0','MSG-SEED-10001','PROVIDER-SEED-10001',NULL,NULL,'SENT','2026-08-08 10:06:00',NULL,'0','2026-08-08 10:00:00','s300.sales01','2026-08-08 10:06:00','s300.sales01','0',NULL,NULL,NULL,'MATCHED');
INSERT IGNORE INTO `t_digital_invoice_event` (`id`,`digital_invoice_id`,`provider_event_id`,`event_type`,`event_at`,`payload_hash`,`signature_valid`,`created_at`,`created_by`) VALUES
('85051','85041','WEBHOOK-SEED-10001','DELIVERED','2026-08-08 10:08:00','2222222222222222222222222222222222222222222222222222222222222222','1','2026-08-08 10:08:00','s300.sales01');
INSERT IGNORE INTO `m_ai_artifact_version` (`id`,`use_case`,`provider`,`model_name`,`prompt_version`,`rule_version`,`config_hash`,`status`,`status_version`,`activated_at`,`retired_at`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('85061','MATCHING','mock','mock-matching-shadow','g11-shadow','rule-2026.08','3333333333333333333333333333333333333333333333333333333333333333','SHADOW','0',NULL,NULL,'2026-08-01 09:00:00','2026-08-01 09:00:00','0'),
('85062','PROPOSAL_DRAFT','mock','mock-proposal-shadow','g11-shadow','rule-2026.08','4444444444444444444444444444444444444444444444444444444444444444','SHADOW','0',NULL,NULL,'2026-08-01 09:00:00','2026-08-01 09:00:00','0');
INSERT IGNORE INTO `t_ai_recommendation_run` (`id`,`trace_id`,`use_case`,`artifact_version_id`,`actor_user_id`,`input_hash`,`redacted_summary_json`,`latency_ms`,`token_input`,`token_output`,`cost_jpy`,`status`,`status_version`,`error_code`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('85071','550e8400-e29b-41d4-a716-446655440001','MATCHING','1','102','5555555555555555555555555555555555555555555555555555555555555555','{"engineerCount":1,"projectCount":1,"redacted":true}','820','240','96','3','SUCCEEDED','0',NULL,'2026-08-06 10:00:00','2026-08-06 10:00:00','0');
INSERT IGNORE INTO `t_ai_recommendation_item` (`id`,`run_id`,`rank_no`,`target_type`,`target_id`,`score`,`explanation_json`,`selected_flag`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('85081','85071','1','ENGINEER','1001','0.9245','{"matchedSkills":["Java","Spring Boot"],"reason":"経験年数と必須スキルが一致"}','1','2026-08-06 10:00:00','2026-08-06 10:00:00','0');
INSERT IGNORE INTO `t_ai_feedback` (`id`,`item_id`,`decision`,`reason_code`,`comment_redacted`,`decided_by`,`decided_at`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('85091','85081','ACCEPT','SKILL_MATCH','提案候補として採用','102','2026-08-06 10:05:00','2026-08-06 10:05:00','2026-08-06 10:05:00','0');
INSERT IGNORE INTO `t_ai_outcome` (`id`,`item_id`,`outcome_type`,`source_type`,`source_id`,`occurred_at`,`original_end_date`,`value_json`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('85092','85081','PROPOSAL_CREATED','PROPOSAL','6001','2026-08-07 11:00:00','2027-03-31','{"proposalStatus":"書類選考中"}','2026-08-07 11:00:00','2026-08-07 11:00:00','0');
INSERT IGNORE INTO `t_ai_evaluation` (`id`,`candidate_version_id`,`baseline_version_id`,`dataset_version`,`metrics_json`,`status`,`status_version`,`approved_by`,`approved_at`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('85101','85061','1','dataset-2026-08','{"precision":0.91,"recall":0.87,"f1":0.89}','PASSED','0','1','2026-08-02 10:00:00','2026-08-02 10:00:00','2026-08-02 10:00:00','0');
INSERT IGNORE INTO `m_lifecycle_template` (`id`,`template_type`,`name`,`description`,`version_no`,`status`,`valid_from`,`valid_to`,`created_at`,`updated_at`,`created_by`,`updated_by`,`deleted_flag`) VALUES
('86001','RESIGNATION','退社手続き（要員）','資産返却と外部アカウント失効を確認する標準手続き','1','ACTIVE','2026-04-01',NULL,'2026-04-01 09:00:00','2026-04-01 09:00:00','101','101','0');
INSERT IGNORE INTO `m_lifecycle_template_task` (`id`,`template_id`,`task_code`,`task_name`,`description`,`relative_due_days`,`assignee_rule`,`assignee_rule_value`,`is_mandatory`,`is_blocking`,`evidence_type`,`is_engineer_visible`,`target_employment_types`,`sort_order`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('86011','86001','RESIGN_ASSET_RETURN','貸与資産返却','PC・セキュリティキーの返却確認','0','ROLE','管理者','1','1','SYSTEM_CHECK','1',NULL,'1','2026-04-01 09:00:00','2026-04-01 09:00:00','0'),
('86012','86001','RESIGN_ACCOUNT_REVOKE','外部アカウント失効','SaaSおよびクラウドアカウントの失効確認','1','ROLE','管理者','1','1','SYSTEM_CHECK','0',NULL,'2','2026-04-01 09:00:00','2026-04-01 09:00:00','0');
INSERT IGNORE INTO `m_lifecycle_template_task_dep` (`id`,`template_id`,`predecessor_task_code`,`successor_task_code`) VALUES
('86021','86001','RESIGN_ASSET_RETURN','RESIGN_ACCOUNT_REVOKE');
INSERT IGNORE INTO `t_lifecycle_case` (`id`,`case_no`,`lifecycle_type`,`engineer_id`,`template_id`,`template_version`,`anchor_date`,`status`,`title`,`remarks`,`applicant_user_id`,`engineer_snapshot_json`,`completed_at`,`completed_by`,`version`,`created_at`,`updated_at`,`created_by`,`updated_by`,`deleted_flag`) VALUES
('86031','LC-202608-0001','RESIGNATION','1001','86001','1','2026-09-30','ON_HOLD','中村 大地 退社手続き','貸与PC返却証跡を再取得中','135','{"engineerId":1001,"engineerName":"中村 大地","organizationId":3005,"salesUserId":102}',NULL,NULL,'0','2026-08-07 09:00:00','2026-08-07 09:00:00','101','101','0');
INSERT IGNORE INTO `t_lifecycle_task` (`id`,`case_id`,`task_code`,`task_name`,`description`,`due_date`,`assignee_user_id`,`assignee_role`,`assignee_name_snapshot`,`is_mandatory`,`is_blocking`,`evidence_type`,`is_engineer_visible`,`status`,`completed_at`,`completed_by`,`completion_comment`,`evidence_data_json`,`approval_request_id`,`version`,`created_at`,`updated_at`,`created_by`,`updated_by`,`deleted_flag`) VALUES
('86041','86031','RESIGN_ASSET_RETURN','貸与資産返却','返却証跡の確認','2026-09-30','101',NULL,'横山 佳代','1','1','SYSTEM_CHECK','1','WAIVED','2026-08-07 14:05:00','1','承認済みの例外免除を適用','{"waiver":true,"remedyDeadline":"2026-09-30"}','65005','0','2026-08-07 09:00:00','2026-08-07 14:05:00','101','101','0'),
('86042','86031','RESIGN_ACCOUNT_REVOKE','外部アカウント失効','SaaSアカウントの失効確認','2026-10-01','101',NULL,'横山 佳代','1','1','SYSTEM_CHECK','0','COMPLETED','2026-08-08 16:00:00','101','失効確認済み','{"systemsChecked":["Google Workspace","GitHub"]}',NULL,'0','2026-08-07 09:00:00','2026-08-08 16:00:00','101','101','0');
INSERT IGNORE INTO `t_lifecycle_task_dep` (`id`,`case_id`,`predecessor_task_id`,`successor_task_id`) VALUES
('86051','86031','86041','86042');
INSERT IGNORE INTO `t_lifecycle_evidence_link` (`id`,`task_id`,`document_id`,`document_version_id`,`verified_at`,`verified_by`,`remarks`) VALUES
('86061','86042','1',NULL,'2026-08-08 16:00:00','101','アカウント失効確認ログ');
INSERT IGNORE INTO `t_lifecycle_event` (`id`,`case_id`,`task_id`,`event_type`,`actor_user_id`,`actor_role_snapshot`,`before_state`,`after_state`,`details_json`,`occurred_at`) VALUES
('86071','86031','86041','TASK_WAIVED','1','管理者','PENDING','WAIVED','{"approvalRequestId":65005,"reason":"返却証跡の再取得中"}','2026-08-07 14:05:00');
INSERT IGNORE INTO `m_report_template` (`id`,`tenant_id`,`template_key`,`template_name`,`status`,`created_by`,`created_at`,`updated_by`,`updated_at`,`deleted_flag`,`version`) VALUES
('87001','default','monthly-management','月次経営管理レポート','ACTIVE','101','2026-08-01 09:00:00','101','2026-08-01 09:00:00','0','1');
INSERT IGNORE INTO `m_report_template_version` (`id`,`tenant_id`,`template_id`,`version_no`,`status`,`section_config_json`,`format_config_json`,`recipient_config_json`,`scope_config_json`,`timezone_id`,`retention_years`,`created_by`,`created_at`,`updated_at`,`published_at`,`deleted_flag`,`version`) VALUES
('87011','default','87001','1','PUBLISHED','{"sections":["sales","gross-profit","utilization","bench"]}','{"format":"PDF"}','{"roles":["管理者","マネージャー"]}','{"ownerType":"COMPANY"}','Asia/Tokyo','7','101','2026-08-01 09:00:00','2026-08-01 09:00:00','2026-08-01 09:00:00','0','1');
INSERT IGNORE INTO `m_report_schedule` (`id`,`tenant_id`,`template_version_id`,`cron_expression`,`timezone_id`,`enabled`,`lock_key`,`next_run_at`,`last_run_at`,`scope_owner_type`,`scope_owner_id`,`organization_scope_json`,`scope_policy_version`,`scope_hash`,`retry_scheduled_at`,`processing_logical_run_at`,`processing_claimed_at`,`failure_count`,`last_error_code`,`last_error_message`,`created_by`,`created_at`,`updated_by`,`updated_at`,`deleted_flag`,`version`) VALUES
('87021','default','87011','0 0 9 1 * ?','Asia/Tokyo','0','management-report-monthly','2026-09-01 09:00:00','2026-08-01 09:00:00','COMPANY',NULL,'{}','scope-v1','6666666666666666666666666666666666666666666666666666666666666666',NULL,NULL,NULL,'0',NULL,NULL,'101','2026-08-01 09:00:00','101','2026-08-01 09:00:00','0','0');
INSERT IGNORE INTO `t_report_run` (`id`,`tenant_id`,`run_key`,`template_id`,`template_version_id`,`schedule_id`,`regeneration_of_run_id`,`snapshot_version`,`principal_type`,`principal_user_id`,`scope_owner_type`,`scope_owner_id`,`organization_scope_json`,`scope_policy_version`,`scope_hash`,`period_from`,`period_to`,`cutoff_kind`,`as_of_at`,`timezone_id`,`data_as_of_at`,`status`,`snapshot_schema_version`,`source_policy_hash`,`failure_code`,`failure_message`,`generated_at`,`created_by`,`created_at`,`updated_at`,`deleted_flag`,`version`) VALUES
('87031','default','monthly-management:2026-07','87001','87011','87021',NULL,'1','SYSTEM_PRINCIPAL','1','COMPANY',NULL,'{}','scope-v1','6666666666666666666666666666666666666666666666666666666666666666','2026-07-01','2026-07-31','MONTHLY_CLOSING','2026-08-01 09:00:00','Asia/Tokyo','2026-08-01 09:00:00','SUCCEEDED','report-1.0','7777777777777777777777777777777777777777777777777777777777777777',NULL,NULL,'2026-08-01 09:30:00','101','2026-08-01 09:00:00','2026-08-01 09:30:00','0','1');
INSERT IGNORE INTO `t_report_section_snapshot` (`id`,`tenant_id`,`run_id`,`section_key`,`section_status`,`fact_type`,`confirmation`,`period_from`,`period_to`,`cutoff_kind`,`as_of_at`,`data_as_of_at`,`freshness_status`,`canonical_service`,`canonical_dto`,`adapter_version`,`source_row_count`,`source_hash`,`value_json`,`error_code`,`error_message`,`snapshot_hash`,`attempt_count`,`created_at`,`updated_at`,`deleted_flag`,`version`) VALUES
('87041','default','87031','sales','SUCCEEDED','実績','確定','2026-07-01','2026-07-31','MONTHLY_CLOSING','2026-08-01 09:00:00','2026-08-01 09:00:00','FRESH','DashboardService','DashboardSummaryDto','report-1.0','12','8888888888888888888888888888888888888888888888888888888888888888','{"sales":52800000,"contracts":42}',NULL,NULL,'9999999999999999999999999999999999999999999999999999999999999999','1','2026-08-01 09:10:00','2026-08-01 09:10:00','0','1');
INSERT IGNORE INTO `t_report_delivery` (`id`,`tenant_id`,`run_id`,`document_id`,`document_version_no`,`recipient_user_id`,`organization_id`,`recipient_scope_json`,`recipient_scope_hash`,`preview_status`,`previewed_at`,`scope_decision`,`delivery_channel`,`delivery_status`,`notification_dedupe_key`,`link_token_hash`,`link_expires_at`,`reauth_required`,`reauthenticated_at`,`attempt_count`,`downloaded_at`,`last_error_code`,`last_error_message`,`created_at`,`updated_at`,`deleted_flag`,`version`) VALUES
('87051','default','87031','1','1','101','3001','{"role":"管理者","organizationId":3001}','aaaaaaaa11111111111111111111111111111111111111111111111111111111','ALLOWED','2026-08-01 09:35:00','ALLOW','IN_APP_LINK','SENT','monthly-management:2026-07:101',NULL,NULL,'1','2026-08-01 09:35:00','1','2026-08-01 09:30:00',NULL,NULL,'2026-08-01 09:35:00','2026-08-01 09:35:00','0','1');
INSERT IGNORE INTO `t_report_section_attempt` (`id`,`tenant_id`,`run_id`,`section_key`,`attempt_no`,`section_status`,`fact_type`,`confirmation`,`period_from`,`period_to`,`cutoff_kind`,`started_at`,`finished_at`,`data_as_of_at`,`freshness_status`,`canonical_service`,`canonical_dto`,`source_row_count`,`source_hash`,`value_json`,`error_code`,`error_message`,`snapshot_hash`,`created_at`,`updated_at`,`deleted_flag`,`version`) VALUES
('87061','default','87031','sales','1','SUCCEEDED','実績','確定','2026-07-01','2026-07-31','MONTHLY_CLOSING','2026-08-01 09:05:00','2026-08-01 09:10:00','2026-08-01 09:00:00','FRESH','DashboardService','DashboardSummaryDto','12','bbbbbbbb22222222222222222222222222222222222222222222222222222222','{"sales":52800000}',NULL,NULL,'cccccccc33333333333333333333333333333333333333333333333333333333','2026-08-01 09:05:00','2026-08-01 09:10:00','0','0');
INSERT IGNORE INTO `t_pwa_client_mutation` (`id`,`client_request_id`,`user_id`,`user_scope_hash`,`operation`,`screen`,`work_month`,`payload_hash`,`base_version`,`status`,`response_json`,`created_at`,`completed_at`) VALUES
('87101','pwa-seed-202608-0001','145','dddddddd44444444444444444444444444444444444444444444444444444444','timesheet.save','timesheet','2026-08','eeeeeeee55555555555555555555555555555555555555555555555555555555','0','COMPLETED','{"code":200,"message":"保存済み"}','2026-08-08 18:30:00','2026-08-08 18:30:00');
INSERT IGNORE INTO `m_certification` (`id`,`tenant_id`,`issuer_key`,`external_code_key`,`name_key`,`identity_key`,`display_name`,`issuer_display`,`external_code`,`expiry_type`,`expiry_months`,`rule_version`,`active_flag`,`created_at`,`updated_at`,`created_by`,`updated_by`,`deleted_flag`) VALUES
('88001','default','IPA','FE','FE','IPA|FE','基本情報技術者試験','情報処理推進機構','FE','FIXED','36','1','1','2026-08-01 09:00:00','2026-08-01 09:00:00','101','101','0');
INSERT IGNORE INTO `m_certification_alias` (`id`,`tenant_id`,`certification_id`,`alias_issuer_key`,`alias_name_key`,`normalized_key`,`valid_from`,`valid_to`,`approved_by`,`approved_at`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('88011','default','88001','IPA','基本情報技術者','ipa|基本情報技術者','2026-04-01',NULL,'101','2026-04-01 09:00:00','2026-04-01 09:00:00','2026-04-01 09:00:00','0');
INSERT IGNORE INTO `t_engineer_certification` (`id`,`tenant_id`,`engineer_id`,`certification_id`,`continuity_group_id`,`acquired_on`,`expires_on`,`expiry_rule_version`,`certificate_number_encrypted`,`certificate_number_key_version`,`certificate_number_cipher_format`,`certificate_number_masked`,`record_state`,`current_flag`,`current_holder_key`,`revision`,`version`,`created_at`,`updated_at`,`created_by`,`updated_by`,`deleted_flag`) VALUES
('88021','default','1001','88001','88021','2024-05-20','2027-05-31','1',NULL,NULL,NULL,'FE-****-2024','ACTIVE','1','88021','1','0','2026-08-01 09:00:00','2026-08-01 09:00:00','145','101','0');
INSERT IGNORE INTO `t_certification_event` (`id`,`tenant_id`,`certification_record_id`,`event_type`,`supersedes_event_id`,`reason`,`actor_user_id`,`actor_role_snapshot`,`occurred_at`,`effective_record_state`,`effective_acquired_on`,`effective_expires_on`,`evidence_document_id`,`evidence_document_version_id`,`evidence_document_hash`,`created_at`,`idempotency_key`) VALUES
('88031','default','88021','VERIFIED',NULL,'証明書を確認済み','101','管理者','2026-08-01 09:10:00','ACTIVE','2024-05-20','2027-05-31','1',NULL,'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','2026-08-01 09:10:00','cert:88021:1:VERIFIED');
INSERT IGNORE INTO `m_training_course` (`id`,`tenant_id`,`provider`,`name`,`description`,`cost_jpy`,`period_days`,`capacity`,`active_flag`,`version`,`created_at`,`updated_at`,`created_by`,`updated_by`,`deleted_flag`) VALUES
('88101','default','社内研修','AWS設計・運用実践','AWS基盤設計と運用の実践研修','55000','2','20','1','0','2026-07-01 09:00:00','2026-07-01 09:00:00','101','101','0'),
('88102','default','外部研修','Reactパフォーマンス改善','Reactアプリケーションの性能改善講座','70000','1','15','1','0','2026-07-01 09:00:00','2026-07-01 09:00:00','101','101','0');
INSERT IGNORE INTO `t_training_course_skill` (`id`,`tenant_id`,`course_id`,`skill_id`,`target_level`,`required_flag`,`created_at`,`deleted_flag`,`updated_at`) VALUES
('88111','default','88101','33','中級','1','2026-07-01 09:00:00','0','2026-07-01 09:00:00'),
('88112','default','88102','15','上級','1','2026-07-01 09:00:00','0','2026-07-01 09:00:00');
INSERT IGNORE INTO `t_learning_plan` (`id`,`tenant_id`,`engineer_id`,`created_by_user_id`,`title`,`goal_description`,`attainment_criteria`,`planned_start_on`,`planned_end_on`,`planned_cost_jpy`,`status`,`approval_request_id`,`version`,`created_at`,`updated_at`,`created_by`,`updated_by`,`deleted_flag`,`expense_request_id`) VALUES
('88121','default','1001','145','AWS資格取得プラン','AWS基盤スキルを強化し、設計工程の担当範囲を広げる','認定試験合格と案件面談で設計経験を説明できること','2026-07-01','2026-10-31','55000','APPROVED',NULL,'0','2026-07-01 09:00:00','2026-08-05 10:00:00','145','145','0','84102');
INSERT IGNORE INTO `t_learning_plan_skill` (`id`,`tenant_id`,`plan_id`,`skill_id`,`target_level`,`target_date`,`created_at`,`deleted_flag`,`updated_at`) VALUES
('88131','default','88121','33','上級','2026-10-31','2026-07-01 09:00:00','0','2026-07-01 09:00:00');
INSERT IGNORE INTO `t_training_enrollment` (`id`,`tenant_id`,`plan_id`,`course_id`,`engineer_id`,`status`,`started_on`,`completed_on`,`score`,`certificate_document_id`,`planned_cost_snapshot`,`version`,`created_at`,`updated_at`,`created_by`,`updated_by`,`deleted_flag`) VALUES
('88141','default','88121','88101','1001','COMPLETED','2026-07-15','2026-07-16','92.5',NULL,'55000','1','2026-07-15 09:00:00','2026-07-16 18:00:00','145','145','0');
INSERT IGNORE INTO `t_training_enrollment_expense` (`id`,`tenant_id`,`enrollment_id`,`expense_request_id`,`relation_reason`,`created_at`,`deleted_flag`,`updated_at`) VALUES
('88151','default','88141','84102','研修受講費用の正本経費','2026-07-16 18:00:00','0','2026-07-16 18:00:00');
INSERT IGNORE INTO `t_engineer_skill_event` (`id`,`tenant_id`,`engineer_id`,`engineer_skill_id`,`skill_id`,`proficiency`,`experience_years`,`event_type`,`effective_from`,`effective_to`,`supersedes_event_id`,`actor_user_id`,`actor_role_snapshot`,`reason`,`occurred_at`,`created_at`) VALUES
('88161','default','1001','5301','1','上級','5','OPEN','2026-08-01',NULL,NULL,'145','要員','seed初期スナップショット','2026-08-01 09:00:00','2026-08-01 09:00:00');
INSERT IGNORE INTO `t_project_skill_event` (`id`,`tenant_id`,`project_id`,`project_skill_id`,`skill_id`,`required_level`,`is_must`,`event_type`,`effective_from`,`effective_to`,`supersedes_event_id`,`actor_user_id`,`actor_role_snapshot`,`reason`,`occurred_at`,`created_at`) VALUES
('88162','default','5001','5201','1','上級','1','OPEN','2026-08-01',NULL,NULL,'102','営業','案件要件初期スナップショット','2026-08-01 09:00:00','2026-08-01 09:00:00');
INSERT IGNORE INTO `t_project_position_event` (`id`,`tenant_id`,`position_id`,`project_id`,`event_type`,`position_no`,`role_name`,`required_count`,`skills_json`,`unit_price_min`,`unit_price_max`,`start_date`,`end_date`,`location`,`allocation_percent`,`priority`,`status`,`source_version`,`effective_from`,`effective_to`,`actor_user_id`,`actor_role_snapshot`,`reason`,`occurred_at`,`created_at`) VALUES
('88163','default','80001','5001','CREATE','POS-2026-001','Javaバックエンドエンジニア','2','[{"skillId":1,"level":"上級","required":true}]','650000','850000','2026-09-01','2027-03-31','東京都千代田区','100','急募','募集中','0','2026-08-09',NULL,'101','管理者','seed初期スナップショット','2026-08-09 09:00:00','2026-08-09 09:00:00');
INSERT IGNORE INTO `t_skill_gap_snapshot` (`id`,`tenant_id`,`as_of_date`,`engineer_id`,`project_id`,`demand_source`,`demand_version`,`supply_version`,`taxonomy_version`,`result_hash`,`result_json`,`created_at`,`created_by`) VALUES
('88171','default','2026-08-01','1001','5001','COMBINED','demand-v1','supply-v1','taxonomy-v1','ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff','{"gaps":[{"skillId":33,"skillName":"AWS","requiredLevel":"上級","currentLevel":"中級"}]}','2026-08-01 09:00:00','101');
INSERT IGNORE INTO `t_engineer_skill_assessment` (`id`,`tenant_id`,`engineer_id`,`skill_id`,`assessment_type`,`proposed_level`,`assessment_state`,`effective_from`,`effective_to`,`actor_user_id`,`reason`,`version`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('88181','default','1001','33','MANAGER','上級','ACCEPTED','2026-08-01',NULL,'135','案件要件を踏まえた上長評価','1','2026-08-01 09:00:00','2026-08-01 09:00:00','0');
INSERT IGNORE INTO `t_learning_decision_event` (`id`,`tenant_id`,`decision_domain`,`source_type`,`source_id`,`human_actor_user_id`,`adverse_use_flag`,`reason`,`snapshot_hash`,`occurred_at`,`created_at`) VALUES
('88191','default','SKILL','ASSESSMENT','88181','135','0','上長評価を学習計画の優先度決定に使用','1111111111111111111111111111111111111111111111111111111111111111','2026-08-02 09:00:00','2026-08-02 09:00:00');
INSERT IGNORE INTO `t_learning_plan_event` (`id`,`tenant_id`,`plan_id`,`source_type`,`source_id`,`event_type`,`amount_snapshot`,`actor_user_id`,`reason`,`occurred_at`,`idempotency_key`,`created_at`) VALUES
('88201','default','88121','PLAN','88121','PLAN_APPROVED','55000','135','研修内容と費用を確認','2026-07-02 09:00:00','learning-plan-88121-approved','2026-07-02 09:00:00');
INSERT IGNORE INTO `t_skill_tag_alias` (`id`,`tenant_id`,`alias_name`,`normalized_alias`,`canonical_skill_id`,`valid_from`,`valid_to`,`approved_by`,`approved_at`,`version`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('88211','default','AWS Certified Solutions Architect','aws-certified-solutions-architect','33','2026-04-01',NULL,'101','2026-04-01 09:00:00','1','2026-04-01 09:00:00','2026-04-01 09:00:00','0');
INSERT IGNORE INTO `m_asset` (`id`,`asset_tag`,`serial_no`,`asset_name`,`category`,`owner_company_id`,`status`,`location`,`purchase_date`,`purchase_price`,`warranty_expiry`,`lease_expiry`,`note`,`version`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('89101','AST-PC-2026-0001','SN-SEED-0001','ThinkPad T14 Gen4','PC',NULL,'ASSIGNED','東京本社・貸与中','2026-04-01','180000','2029-03-31',NULL,'要員貸与中','1','2026-04-01 09:00:00','2026-08-01 09:00:00','0'),
('89102','AST-PC-2026-0002','SN-SEED-0002','MacBook Pro 14','PC',NULL,'LOST','東京本社・紛失対応','2026-04-01','240000','2029-03-31',NULL,'紛失インシデント対応中','1','2026-04-01 09:00:00','2026-08-08 10:00:00','0');
INSERT IGNORE INTO `t_asset_assignment` (`id`,`asset_id`,`assignee_type`,`assignee_id`,`start_date`,`expected_return_date`,`actual_return_date`,`handover_evidence_doc_id`,`return_evidence_doc_id`,`status`,`note`,`version`,`created_by`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('89111','89101','ENGINEER','1001','2026-04-01','2028-03-31',NULL,NULL,NULL,'ACTIVE','返却予定日を登録済み','1','101','2026-04-01 09:00:00','2026-04-01 09:00:00','0'),
('89112','89102','ENGINEER','1002','2026-04-01','2026-07-31',NULL,NULL,NULL,'OVERDUE','返却期限超過後に紛失インシデントへ遷移','1','101','2026-04-01 09:00:00','2026-08-08 10:00:00','0');
INSERT IGNORE INTO `t_asset_event` (`id`,`asset_id`,`event_type`,`event_time`,`actor_user_id`,`assignee_type`,`assignee_id`,`from_status`,`to_status`,`evidence_doc_id`,`event_summary`,`details_json`,`created_at`) VALUES
('89121','89101','CREATED','2026-04-01 09:00:00','101',NULL,NULL,NULL,'IN_STOCK',NULL,'資産台帳へ登録','{"source":"seed"}','2026-04-01 09:00:00'),
('89122','89101','ASSIGNED','2026-04-01 09:05:00','101','ENGINEER','1001','IN_STOCK','ASSIGNED',NULL,'中村 大地へ貸与','{"assignmentId":89111}','2026-04-01 09:05:00'),
('89123','89102','REPORTED_LOST','2026-08-08 10:00:00','146','ENGINEER','1002','ASSIGNED','LOST',NULL,'紛失報告を受領','{"incidentId":89141}','2026-08-08 10:00:00');
INSERT IGNORE INTO `t_asset_inventory_run` (`id`,`inventory_code`,`title`,`target_date`,`status`,`total_assets`,`matched_count`,`discrepancy_count`,`missing_count`,`conducted_by`,`completed_at`,`version`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('89131','INV-2026-H1-SEED','2026年度上期棚卸し','2026-08-08','IN_PROGRESS','2','1','1','0','101',NULL,'1','2026-08-08 09:00:00','2026-08-08 09:00:00','0');
INSERT IGNORE INTO `t_asset_inventory_item` (`id`,`inventory_run_id`,`asset_id`,`expected_status`,`expected_location`,`observed_status`,`observed_location`,`discrepancy_type`,`discrepancy_reason`,`resolution_action`,`checked_by`,`checked_at`,`created_at`,`updated_at`) VALUES
('89132','89131','89101','ASSIGNED','中村 大地','ASSIGNED','中村 大地','MATCH',NULL,NULL,'101','2026-08-08 09:30:00','2026-08-08 09:00:00','2026-08-08 09:30:00'),
('89133','89131','89102','LOST','池田 翔太','MISSING','未確認','MISSING','紛失インシデントへ引継ぎ','リモートワイプ確認待ち','101','2026-08-08 09:35:00','2026-08-08 09:00:00','2026-08-08 09:35:00');
INSERT IGNORE INTO `m_external_account_system` (`id`,`system_code`,`system_name`,`system_type`,`auth_type`,`is_active`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('89151','GOOGLE_WORKSPACE','Google Workspace','SAAS_MAIL','SAML_OIDC','1','2026-04-01 09:00:00','2026-04-01 09:00:00','0'),
('89152','GITHUB','GitHub Enterprise','SAAS_COLLAB','OAUTH2','1','2026-04-01 09:00:00','2026-04-01 09:00:00','0');
INSERT IGNORE INTO `t_external_account_reference` (`id`,`system_id`,`account_identifier`,`assignee_type`,`assignee_id`,`permission_level`,`status`,`provisioned_at`,`idempotency_key`,`retry_count`,`next_retry_at`,`last_error_message`,`revoke_requested_at`,`revoke_confirmed_at`,`revoke_confirmed_by`,`external_sync_status`,`sync_error_message`,`version`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('89161','89151','s300.member001@ses.local','ENGINEER','1001','MEMBER','ACTIVE','2026-04-01 09:00:00','ext-seed-89161','0',NULL,NULL,NULL,NULL,NULL,'NONE',NULL,'0','2026-04-01 09:00:00','2026-08-01 09:00:00','0');
INSERT IGNORE INTO `m_license_plan` (`id`,`plan_code`,`plan_name`,`system_id`,`seat_limit`,`allocated_count`,`cost_per_seat`,`cost_center_id`,`expiry_date`,`status`,`version`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('89171','LIC-GWS-BUSINESS','Google Workspace Business','89151','300','1','1800','4001','2027-03-31','ACTIVE','0','2026-04-01 09:00:00','2026-08-01 09:00:00','0');
INSERT IGNORE INTO `t_license_assignment` (`id`,`plan_id`,`assignee_type`,`assignee_id`,`account_reference_id`,`assigned_date`,`released_date`,`status`,`version`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('89181','89171','ENGINEER','1001','89161','2026-04-01',NULL,'ACTIVE','0','2026-04-01 09:00:00','2026-04-01 09:00:00','0');
INSERT IGNORE INTO `t_asset_lost_incident` (`id`,`asset_id`,`reported_at`,`reported_by`,`incident_details`,`remote_wipe_status`,`remote_wipe_requested_at`,`remote_wipe_executed_at`,`remote_wipe_confirmed_at`,`police_report_number`,`insurance_claim_status`,`insurance_claimed_at`,`version`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('89141','89102','2026-08-08 10:00:00','146','貸与端末の所在が確認できず、本人へ再確認を依頼中','REQUESTED','2026-08-08 10:10:00',NULL,NULL,'POLICE-SEED-2026-0808','APPLIED','2026-08-08 11:00:00','1','2026-08-08 10:00:00','2026-08-08 11:00:00','0');
INSERT IGNORE INTO `t_asset_offboarding_waiver` (`id`,`engineer_id`,`approval_request_id`,`reason`,`approved_by`,`approved_at`,`created_at`,`updated_at`,`deleted_flag`) VALUES
('89191','1001','65005','返却証跡の再取得中のため、退社手続き上の資産返却blockerを一時免除','1','2026-08-07 14:05:00','2026-08-07 14:05:00','2026-08-07 14:05:00','0');

SET FOREIGN_KEY_CHECKS = 1;
