-- NF-03: 追加予算は独立承認と監査eventを伴う。申請時planned costは不変snapshot。
ALTER TABLE t_learning_plan
    ADD COLUMN amended_cost_jpy DECIMAL(12,0) NULL COMMENT '追加承認後の予算上限';

ALTER TABLE t_learning_plan
    ADD COLUMN amendment_approval_request_id BIGINT NULL COMMENT '追加予算承認申請ID';

SELECT 1;
