package com.ses.service.freee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.freee.hr.FreeeBonusStatement;
import com.ses.dto.freee.hr.FreeeHrEmployee;
import com.ses.dto.freee.hr.FreeePayrollItem;
import com.ses.dto.freee.hr.FreeeSalaryStatement;
import com.ses.dto.freee.hr.FreeeStatementPage;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * HFP-01-004: freee人事労務responseのtyped parse・normalize adapter。
 *
 * <p>HTTP/tokenを所有しない純粋adapter。固定OpenAPI commit
 * （52c69a6819ef14979a31b342123df816cb72c742）のroot/fieldを実装正本とし、</p>
 * <ul>
 *   <li>未知の追加propertyは許容（後方互換、HFP-01-R01-3）</li>
 *   <li>必須root / id / total_count / 配列型は明示検証し、欠落はprovider契約エラー（HTTP 200でも空扱いしない）</li>
 *   <li>金額はJSON stringをtrim後にBigDecimalへ厳密変換。nullは保持、空文字・非数値はcontract error</li>
 * </ul>
 */
@Component
public class FreeeHrContractAdapter {

    public static final String ROOT_EMPLOYEE_PAYROLL_STATEMENTS = "employee_payroll_statements";
    public static final String FIELD_TOTAL_COUNT = "total_count";

    private final ObjectMapper objectMapper = createMapper();

    /** 公式JSONはsnake_case。Java field（camelCase）へ正しく対応付ける。 */
    private static ObjectMapper createMapper() {
        return new ObjectMapper()
                .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
    }

    /**
     * 全期間従業員（raw配列）。公式のrootはwrapperなしの配列。
     * 各要素のidを必須検証する。
     */
    public List<FreeeHrEmployee> companyEmployees(JsonNode root) {
        requireArray(root, "従業員一覧");
        List<FreeeHrEmployee> out = new ArrayList<>();
        for (JsonNode node : root) {
            if (!node.path("id").isNumber()) {
                throw contractError("従業員一覧のid欠落");
            }
            FreeeHrEmployee e = new FreeeHrEmployee();
            e.setId(node.path("id").asLong());
            e.setNum(textOrNull(node, "num"));
            e.setDisplayName(textOrNull(node, "display_name"));
            e.setEntryDate(textOrNull(node, "entry_date"));
            e.setRetireDate(textOrNull(node, "retire_date"));
            if (node.hasNonNull("payroll_calculation")) {
                e.setPayrollCalculation(node.path("payroll_calculation").asBoolean());
            }
            out.add(e);
        }
        return out;
    }

    /** 給与一覧1ページ（root employee_payroll_statements + total_count必須）。 */
    public FreeeStatementPage<FreeeSalaryStatement> salaryPage(JsonNode root) {
        List<FreeeSalaryStatement> items = new ArrayList<>();
        int totalCount = pageMetadata(root, items);
        for (JsonNode node : root.path(ROOT_EMPLOYEE_PAYROLL_STATEMENTS)) {
            if (!node.path("id").isNumber()) {
                throw contractError("給与明細のid欠落");
            }
            FreeeSalaryStatement s = objectMapper.convertValue(node, FreeeSalaryStatement.class);
            validateAmounts(s.getGrossPaymentAmount(), s.getTotalDeductionAmount(),
                    s.getNetPaymentAmount(), s.getTotalDeductionEmployerShare());
            validateItems(s.getPayments());
            validateItems(s.getDeductions());
            validateItems(s.getDeductionsEmployerShare());
            items.add(s);
        }
        return new FreeeStatementPage<>(items, totalCount);
    }

    /** 賞与一覧1ページ（root employee_payroll_statements + total_count必須）。 */
    public FreeeStatementPage<FreeeBonusStatement> bonusPage(JsonNode root) {
        List<FreeeBonusStatement> items = new ArrayList<>();
        int totalCount = pageMetadata(root, items);
        for (JsonNode node : root.path(ROOT_EMPLOYEE_PAYROLL_STATEMENTS)) {
            if (!node.path("id").isNumber()) {
                throw contractError("賞与明細のid欠落");
            }
            FreeeBonusStatement s = objectMapper.convertValue(node, FreeeBonusStatement.class);
            validateAmounts(s.getGrossPaymentAmount(), s.getTotalDeductionAmount(), s.getNetPaymentAmount());
            validateItems(s.getAllowances());
            validateItems(s.getDeductions());
            items.add(s);
        }
        return new FreeeStatementPage<>(items, totalCount);
    }

    /** root/配列型/total_countを検証し、total_countを返す。 */
    private int pageMetadata(JsonNode root, List<?> sink) {
        if (root == null || !root.isObject()) {
            throw contractError("明細root欠落");
        }
        JsonNode array = root.path(ROOT_EMPLOYEE_PAYROLL_STATEMENTS);
        JsonNode total = root.path(FIELD_TOTAL_COUNT);
        if (!array.isArray()) {
            throw contractError("明細配列欠落");
        }
        if (!total.isNumber()) {
            throw contractError("total_count欠落");
        }
        return total.asInt();
    }

    private void requireArray(JsonNode root, String what) {
        if (root == null || !root.isArray()) {
            throw contractError(what + "のrootが配列でない");
        }
    }

    private void validateItems(List<FreeePayrollItem> items) {
        if (items == null) {
            return;
        }
        for (FreeePayrollItem item : items) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                throw contractError("明細項目のname欠落");
            }
            // 項目名・金額値はdetailへ含めない（REV-001: 給与情報の漏洩防止）
            strictAmount(item.getAmount(), "明細項目の金額");
        }
    }

    private void validateAmounts(String... amounts) {
        for (String amount : amounts) {
            strictAmount(amount, "合計金額");
        }
    }

    /**
     * nullは保持。空文字・非数値はprovider契約エラー。
     * detailにはfield種別だけを載せ、providerの生金額文字列・項目名を含めない（REV-001 / R09-3）。
     */
    private void strictAmount(String amount, String what) {
        if (amount == null) {
            return;
        }
        if (amount.trim().isEmpty()) {
            throw contractError(what + "が空文字");
        }
        try {
            new BigDecimal(amount.trim());
        } catch (NumberFormatException e) {
            throw contractError(what + "が数値でない");
        }
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asText();
    }

    private BusinessException contractError(String detail) {
        // detailはfield名のみ。raw body・tokenは含めない。
        return BusinessException.of(502, "error.payroll.contractError", detail);
    }
}
