package com.ses.service.compliance;

import com.ses.service.SystemConfigService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 抵触日算定（design §5.2 期間代数）。
 *
 *  - 連続更新: 空白がクーリング期間未満ならchainを通算し、更新のたびに0からリセットしない。
 *  - クーリング: 空白（前契約終了日の翌日〜次契約開始日の前日）が規定日数以上なら通算をリセット。
 *  - 組織単位の変更: 組織単位が変わった契約は組織単位のカウントでは別chain（実体同一はfindingで人に確認）。
 *  - 同日開始の新契約: 前契約を前日で閉じてchainを繋ぐ。
 *  - 未来開始の契約: 現在の抵触日算定に含める（先読み警告）。
 *  - 複数契約が同時並行: 各契約をchainへ通算し、最も早い抵触日を採る。
 *
 * 上限月数（事業所単位/組織単位）とクーリング日数はm_system_config値。
 * 法定値の最終確認はGATE-T060-COOLING / GATE-T066-FIELD-SEMANTICS（本実装はconfig既定値で駆動し、コードへ直書きしない）。
 */
@Component
public class LimitationDateCalculator {

    /** クーリング期間（日）のconfig key。値はGATE-T060-COOLINGで確定する。 */
    public static final String CONFIG_COOLING_DAYS = "compliance.cooling-period-days";
    /** 事業所単位の派遣可能期間上限（月）。値はGATE-T066-FIELD-SEMANTICSで確定する。 */
    public static final String CONFIG_WORKPLACE_LIMIT_MONTHS = "compliance.limitation.workplace-months";
    /** 組織単位（個人単位）の派遣可能期間上限（月）。値はGATE-T066-FIELD-SEMANTICSで確定する。 */
    public static final String CONFIG_ORGANIZATION_LIMIT_MONTHS = "compliance.limitation.organization-months";

    private final SystemConfigService systemConfigService;

    public LimitationDateCalculator(SystemConfigService systemConfigService) {
        this.systemConfigService = systemConfigService;
    }

    /** 契約chainの1契約分（同一要員×同一事業所/組織単位の判定用）。 */
    public record ChainContract(Long contractId, LocalDate start, LocalDate end,
                                Long customerId, Long workplaceId, String organizationUnit) {
    }

    /** 算定結果。算定不能（要員/組織単位の識別子がない等）はnull。 */
    public record LimitationDates(LocalDate workplaceDate, LocalDate organizationDate) {
    }

    /**
     * 対象契約が属するchainから2種の抵触日を算定する。
     * chainは同一engineer（呼び出し側がengineerIdで絞り込んだ契約群）を渡す。
     * 対象契約自身と同一の事業所（workplaceId）のchainから事業所単位抵触日、
     * 同一の組織単位（organizationUnit）のchainから組織単位抵触日を求める。
     */
    public LimitationDates compute(LocalDate contractStart, Long workplaceId, String organizationUnit,
                                   List<ChainContract> allContracts) {
        if (contractStart == null) {
            return new LimitationDates(null, null);
        }
        LocalDate workplaceDate = null;
        if (workplaceId != null) {
            List<ChainContract> chain = buildChain(contractStart, c -> workplaceId.equals(c.workplaceId()), allContracts);
            workplaceDate = chain == null ? null : chainEndLimit(chain,
                    configInt(CONFIG_WORKPLACE_LIMIT_MONTHS, 36));
        }
        LocalDate organizationDate = null;
        if (organizationUnit != null && !organizationUnit.isBlank()) {
            List<ChainContract> chain = buildChain(contractStart,
                    c -> organizationUnit.equals(c.organizationUnit()), allContracts);
            organizationDate = chain == null ? null : chainEndLimit(chain,
                    configInt(CONFIG_ORGANIZATION_LIMIT_MONTHS, 12));
        }
        return new LimitationDates(workplaceDate, organizationDate);
    }

    /**
     * 対象契約の開始日を含むchainを返す。
     * chain規則: 前chainの実効終了日の翌日からクーリング期間未満の空白で次の契約が始まるなら連結。
     * 対象契約がどのchainにも属さない（孤立・算定不能）場合はnull。
     */
    List<ChainContract> buildChain(LocalDate contractStart, java.util.function.Predicate<ChainContract> sameScope,
                                   List<ChainContract> allContracts) {
        List<ChainContract> scoped = allContracts.stream()
                .filter(sameScope)
                .filter(c -> c.start() != null)
                .sorted(Comparator.comparing(ChainContract::start))
                .toList();
        if (scoped.isEmpty()) {
            return null;
        }
        int coolingDays = configInt(CONFIG_COOLING_DAYS, 30);
        List<List<ChainContract>> chains = new ArrayList<>();
        List<ChainContract> current = new ArrayList<>();
        LocalDate chainEffectiveEnd = null;
        for (ChainContract c : scoped) {
            boolean joins;
            if (current.isEmpty()) {
                joins = true;
            } else {
                LocalDate prevEnd = chainEffectiveEnd;
                if (prevEnd == null) {
                    joins = true;
                } else {
                    long gap = java.time.temporal.ChronoUnit.DAYS.between(prevEnd, c.start()) - 1;
                    joins = gap < coolingDays;
                }
            }
            if (joins) {
                current.add(c);
                if (chainEffectiveEnd == null || (c.end() != null && c.end().isAfter(chainEffectiveEnd))) {
                    chainEffectiveEnd = c.end();
                }
            } else {
                chains.add(current);
                current = new ArrayList<>();
                current.add(c);
                chainEffectiveEnd = c.end();
            }
        }
        if (!current.isEmpty()) {
            chains.add(current);
        }
        for (List<ChainContract> chain : chains) {
            // 対象契約（開始日=contractStart）が属するchainを選ぶ。
            // 並行契約（同日開始）は同一chainへ通算され、いずれも同じchainに属する。
            if (chain.stream().anyMatch(c -> c.start().equals(contractStart))) {
                return chain;
            }
        }
        for (List<ChainContract> chain : chains) {
            // 対象契約がscoped内に見つからない場合は、contractStartを実効期間が覆うchainを採用する。
            boolean covers = chain.stream().anyMatch(c -> !c.start().isAfter(contractStart)
                    && (c.end() == null || !c.end().isBefore(contractStart)));
            if (covers) {
                return chain;
            }
        }
        return null;
    }

    /** chain先頭から上限月数後の日付（抵触日）。クーリング未満の空白は継続として時計を止めない（安全側）。 */
    private LocalDate chainEndLimit(List<ChainContract> chain, int limitMonths) {
        if (chain.isEmpty() || limitMonths <= 0) {
            return null;
        }
        return chain.get(0).start().plusMonths(limitMonths);
    }

    private int configInt(String key, int defaultValue) {
        return systemConfigService.getInt(key, defaultValue);
    }
}
