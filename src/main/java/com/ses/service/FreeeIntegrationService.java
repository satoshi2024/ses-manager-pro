package com.ses.service;
import com.ses.dto.payroll.FreeeEmployeeDto;
import com.ses.dto.payroll.PayrollStatementDto;
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
}
