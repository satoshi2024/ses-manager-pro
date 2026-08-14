package com.ses.dto.freee.hr;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * freee給与・賞与一覧の1ページ分（公式 root `employee_payroll_statements` + `total_count`）。
 */
@Getter
@RequiredArgsConstructor
public class FreeeStatementPage<T> {
    private final List<T> items;
    private final int totalCount;
}
