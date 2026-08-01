package com.ses.service.security;

import java.time.LocalDate;
import java.util.Set;

/** CRMの顧客母集団を一箇所で解決するサービス。認可とSQL境界の両方から利用する。 */
public interface CrmScopeService {

    /** 管理者またはスコープ無効の一般ロールなど、顧客ID条件を付けない状態。 */
    boolean hasFullAccess();

    /** CRMを利用できるロールか。HR・要員などは空母集団として扱う。 */
    boolean canUseCrm();

    /** 対象日時点のCRM顧客母集団。全件権限では空集合（＝条件なし）を返す。 */
    Set<Long> allowedCustomerIds(LocalDate asOf);

    Set<Long> allowedOwnerIds(LocalDate asOf);

    boolean isCustomerAllowed(Long customerId, LocalDate asOf);

    void assertAllowedCustomer(Long customerId, LocalDate asOf);
}
