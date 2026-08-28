-- ===================================================================
-- V128: NF-03 A2/B1 研修費を既存ExpenseRequest正本へ受け渡すための科目許可
-- ===================================================================

ALTER TABLE t_expense_request
    DROP CHECK chk_expense_category,
    ADD CONSTRAINT chk_expense_category
        CHECK (category IN ('交通費', '立替経費', '研修費'));
