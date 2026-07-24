package com.ses.service;
import com.ses.dto.payroll.FreeeEmployeeDto;
import com.ses.dto.payroll.PayrollStatementDto;
import com.ses.dto.reconciliation.BankDepositDto;
import java.time.LocalDate;
import java.util.List;
public interface FreeeIntegrationService {
    String authorizationUrl(String state);
    void handleCallback(String code, String state, Long userId);
    boolean connected();
    void disconnect();
    List<FreeeEmployeeDto> employees();
    void link(Long engineerId, String employeeId, Long userId);
    void unlink(Long engineerId);
    List<PayrollStatementDto> statements(int year, int month, String type);
    void refresh();
    /** 銀行入金明細（freee会計の入金取引）を期間指定で取得する（入金消込 / FR-09）。 */
    List<BankDepositDto> bankDeposits(LocalDate from, LocalDate to);
}
