package com.ses.privacy.foundation;

import com.ses.mapper.SysUserMapper;
import com.ses.service.security.AuthorizationService;
import com.ses.service.security.LegalEntityContextService;
import org.springframework.core.env.Environment;

import java.time.Clock;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * foundation の production 部品を package 外から生成する唯一の入口（production-bridge-phased-plan.md §3.1.1）。
 *
 * <p>生成前に {@link ProductionEnvironmentGuard} で prod profile を要求する（prod 以外では例外）。
 * 生成する service・caller provider・verifier は package-private のままで、package 外から直接は作れない。
 * Spring アノテーションは持たず、Spring 登録は {@code com.ses.privacy.production} 側が行う。
 * 生成時に DB・catalog・source・trust registry へ触れない（全て照会時に lazy に読む）。
 */
public final class ProductionPrivacyComponents {

    private ProductionPrivacyComponents() {
    }

    /**
     * production の読み取り専用 policy 照会 port を生成する。
     *
     * @param environment            起動環境（prod profile 必須）
     * @param sourceEnabled          公開スイッチ（{@code privacy.retention.enabled}）。照会のたびに読む
     * @param authorizationService   action 権限の判定部品。照会時に取得し、取れなければ拒否する
     * @param authorizationTenantId  {@code AuthorizationService} が group を評価する設定tenant
     */
    public static PolicyReadonlyQueryPort productionPolicyReadonlyQuery(
            Environment environment,
            BooleanSupplier sourceEnabled,
            LegalEntityContextService legalEntityContextService,
            SysUserMapper sysUserMapper,
            Supplier<AuthorizationService> authorizationService,
            String authorizationTenantId,
            ProductionPolicyCatalogPort catalog,
            CurrentPolicySource source,
            ProductionPolicyTrustRegistry trustRegistry,
            RevocationStatusProvider revocations,
            Clock clock) {
        ProductionEnvironmentGuard guard = new ProductionEnvironmentGuard(
                Objects.requireNonNull(environment, "environment must not be null"));
        guard.requireProductionProfile();
        SecurityContextCallerContextProvider callerProvider = new SecurityContextCallerContextProvider(
                legalEntityContextService, sysUserMapper, authorizationService, authorizationTenantId, clock);
        return new ProductionPolicyReadonlyQueryService(sourceEnabled, callerProvider, catalog, source,
                trustRegistry, revocations, guard, clock);
    }
}
