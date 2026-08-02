package com.ses.service.approval;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 対象種別・組織・金額帯・申請者から1件のrouteを決め、各stepの承認者を申請時点で確定するresolver
 * （design §6.2の金額帯境界・複数route該当時の決定順を実装する）。
 */
public interface RouteResolverService {

    /**
     * @param requestType    対象種別
     * @param organizationId 対象組織（NULLも可）
     * @param amountSnapshot 申請金額（NULL=金額なし申請、負数は絶対値で判定）
     * @param applicantId    申請者（自己承認となる候補をstepから除外するため）
     * @param asOf           route有効期間の判定基準日
     * @return 解決済みroute（各stepの承認者user idまで確定済み）
     * @throws com.ses.common.exception.BusinessException 該当routeなし、またはいずれかのstepで
     *         承認者が1人も解決できない場合（R3.4）。管理者への通知は呼出側(engine)が行う。
     */
    ResolvedRoute resolve(String requestType, Long organizationId, BigDecimal amountSnapshot,
                          Long applicantId, LocalDate asOf);
}
