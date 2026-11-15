package com.ses.privacy.foundation;

import com.ses.mapper.SysUserMapper;
import com.ses.service.security.AuthorizationService;
import com.ses.service.security.LegalEntityContextService;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
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

    /**
     * 独立 Journal インスタンスへの本番 adapter を生成する（NF07-A1J、production-bridge-phased-plan.md §3.3）。
     *
     * <p>合成 {@code JdbcPrivacyJournalAdapter} とは別 class・別入口で、合成層のコードは使わない（I-6）。
     * {@code journalDataSource} は Journal 専用の接続（{@code PrivacyJournalConnections} の内部 pool）だけを渡す。
     * application DB の {@code DataSource} を渡さないことは、起動時検査（{@code PrivacyJournalStartupVerifier}）の
     * {@code server_uuid}・schema・権限の照合で検出する。生成時に DB へ触れない。
     *
     * @param environment       起動環境（prod profile 必須。prod 以外では {@link SecurityException}）
     * @param journalDataSource Journal 専用の接続
     * @param clock             event 時刻の時計
     */
    public static PrivacyJournalPort productionPrivacyJournal(Environment environment, DataSource journalDataSource,
                                                              Clock clock) {
        ProductionEnvironmentGuard guard = new ProductionEnvironmentGuard(
                Objects.requireNonNull(environment, "environment must not be null"));
        return new ProductionPrivacyJournalAdapter(journalDataSource, guard, clock);
    }

    /** 本番 Journal の append・照会の transaction 上限（{@code privacy.journal.pending-timeout} の下限）。 */
    public static Duration journalAppendTimeout() {
        return ProductionPrivacyJournalAdapter.APPEND_TRANSACTION_TIMEOUT;
    }

    /** 本番 Journal の表名（起動時検査の権限・engine 照合に使う）。 */
    public static List<String> journalTables() {
        return ProductionPrivacyJournalAdapter.JOURNAL_TABLES;
    }
}
