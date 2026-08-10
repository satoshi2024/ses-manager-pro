package com.ses.service;
import com.fasterxml.jackson.databind.JsonNode;
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
    /**
     * OAuth/refresh共通基盤を使う認証付きGET。401時はrefreshを1回だけ実行して再試行し、
     * 429はexponential backoff、timeout/5xxは503 BusinessExceptionへ変換する。
     * 秘密情報（access/refresh token）はログへ出力しない。
     * S11 T072（freee/provider sync）が共通基盤として再利用する。
     */
    JsonNode apiGet(String path);
    /**
     * OAuth/refresh共通基盤を使う認証付きPOST。冪等キーと相関IDをヘッダーへ付与する。
     * 401時はrefreshを1回だけ実行して再試行し、429はexponential backoff、timeout/5xxは503へ変換する。
     */
    JsonNode apiPost(String path, Object body, String idempotencyKey, String correlationId);
}
