package com.ses.service;
import com.fasterxml.jackson.databind.JsonNode;
import com.ses.dto.payroll.FreeeConnectionStatusDto;
import com.ses.dto.payroll.FreeeEmployeeDto;
import com.ses.dto.payroll.PayrollEngineerCandidateDto;
import com.ses.dto.payroll.PayrollStatementDto;
import java.util.List;
public interface FreeeIntegrationService {
    String authorizationUrl(String state);
    void handleCallback(String code, String state, Long userId);
    /**
     * 認可codeをtoken endpointで交換し、access/refresh token・有効期限・company_idの存在を検証して返す。
     * 保存はしない（給与/会計の保存先と事業所検証は呼出側）。失敗はBusinessException（provider詳細は出さない）。
     */
    com.ses.dto.freee.FreeeTokenGrant exchangeAuthorizationCode(String code);
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

    /** 接続中の事業所ID（freee人事労務のcompany_id）。未接続ならnull。 */
    Long connectedCompanyId();

    /**
     * 人事労務API（hrApiBase）への認証付きGET。queryはURLエンコードして付与する。
     * 401はrefresh 1回＋再試行、429はbackoff、5xx/timeoutは最大2回retryの後に503。
     */
    JsonNode hrApiGet(String path, java.util.Map<String, String> query);

    /**
     * 人事労務API（hrApiBase）への認証付きPUT（勤怠サマリ等の上書き更新）。PUTは同じbodyなら結果が同じため
     * 5xx/timeoutはretryしない（送れたか不明な状態で多重送信しない。呼出側が再実行する）。
     */
    JsonNode hrApiPut(String path, Object body, String correlationId);
}
