package com.ses.service;
import com.fasterxml.jackson.databind.JsonNode;
import com.ses.dto.payroll.FreeeConnectionStatusDto;
import com.ses.dto.payroll.FreeeEmployeeDto;
import com.ses.dto.payroll.PayrollEngineerCandidateDto;
import com.ses.dto.payroll.PayrollStatementDto;
import com.ses.dto.reconciliation.BankDepositDto;
import java.time.LocalDate;
import java.util.List;
public interface FreeeIntegrationService {
    String authorizationUrl(String state);
    void handleCallback(String code, String state, Long userId);
    /**
     * 接続状態を返す。statusは DISCONNECTED / CONNECTED / REAUTH_REQUIRED / MISCONFIGURED のいずれかで、
     * 日本語の次アクション（message key）をactionへ載せる。company ID・token・期限は返さない。
     */
    FreeeConnectionStatusDto connectionStatus();
    /**
     * status == CONNECTED のときだけtrue（単なる接続rowの存在ではない）。
     * S11/S15/CashFlowのboolean contract。
     */
    boolean connected();
    /**
     * freee公式revoke endpointへの失効要求（access/refresh双方）が成功、または既に無効と確認できた
     * 場合だけローカル接続を削除する。一時的なprovider障害では削除せずBusinessExceptionを投げる。
     */
    void disconnect();
    List<FreeeEmployeeDto> employees();
    /** 給与対応付けの内部要員候補（非BP・未削除）。給与専用API。 */
    List<PayrollEngineerCandidateDto> engineerCandidates();
    void link(Long engineerId, String employeeId, Long userId);
    void unlink(Long engineerId);
    List<PayrollStatementDto> statements(int year, int month, String type);
    /** 本人専用の給与明細取得。外部取得境界で当該engineerIdのみを取得・materializeする（R1-P1-04）。 */
    PayrollStatementDto statementForEngineer(Long engineerId, int year, int month, String type);
    /**
     * 期限ベースのrefresh。短TXでrow-lock後に再確認し、別threadが既に更新して有効期限に余裕がある場合は
     * 外部refreshせずreturnする。HTTPはTX外（S15-P1-01 / HFP-01-R03-3）。
     */
    void refresh();
    /**
     * 401でaccess tokenが拒否された場合のrefresh。ローカル期限に依らず必ず外部refreshを1回行う。
     * 短TX lock → HTTP外 → 短TX CAS により、同一refresh tokenを並行・再試行で二度使わない（HFP-01-R03-3/AC04）。
     */
    void refreshForced();
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
