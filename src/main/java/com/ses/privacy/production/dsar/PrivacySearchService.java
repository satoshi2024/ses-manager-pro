package com.ses.privacy.production.dsar;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.privacy.PiiDataElement;
import com.ses.mapper.privacy.PiiDataElementMapper;
import com.ses.privacy.production.PrivacyCoverageReport;
import com.ses.privacy.production.ProductionPrivacyProviderRegistry;
import com.ses.privacy.production.provider.PrivacyRecord;
import com.ses.privacy.production.provider.PrivacySubjectRef;
import com.ses.privacy.production.provider.ProductionPrivacyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * DSAR の read-only 検索（NF07-A2b、production-bridge-phased-plan.md §3.5.2）。F2 の本番 provider だけを呼び、書込み・外部呼出しをしない。
 *
 * <ul>
 *   <li>tenant は呼出し側（{@link PrivacyExportPackageService}）が照会者の tenant を明示する。provider は tenant で絞るが、
 *       行が {@code tenant_id} 列を持ち、それが照会の tenant と違えば行を捨てる（provider の誤りで他 tenant の記録を草案に入れない。
 *       件数だけを WARN {@value #LOG_CODE_TENANT_MISMATCH} に出す）。</li>
 *   <li>catalog（{@code m_pii_data_element}）の照会の tenant の行のうち、provider が扱わない data element は「要手動確認」として
 *       理由コード（{@link PrivacyCoverageReport#PROVIDER_UNREGISTERED} 等）付きで報告する（検索の対象外であることを隠さない）。</li>
 *   <li>provider・catalog の照会に失敗したら 503 {@code error.privacy.dsarSourceUnavailable}（記録の欠けた草案を作らない）。</li>
 * </ul>
 * 返す行は値を含むため、package の作成にだけ使い、画面・API・監査・log には出さない（件数と理由コードだけを出す）。
 */
public class PrivacySearchService {

    private static final Logger log = LoggerFactory.getLogger(PrivacySearchService.class);

    /** provider が他 tenant の行を返した（行は捨てた）。 */
    static final String LOG_CODE_TENANT_MISMATCH = "NF07_DSAR_SEARCH_TENANT_MISMATCH";
    static final String ERROR_SOURCE_UNAVAILABLE = "error.privacy.dsarSourceUnavailable";
    private static final String TENANT_COLUMN = "tenant_id";

    private final ProductionPrivacyProviderRegistry registry;
    private final PiiDataElementMapper dataElementMapper;

    public PrivacySearchService(ProductionPrivacyProviderRegistry registry, PiiDataElementMapper dataElementMapper) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.dataElementMapper = Objects.requireNonNull(dataElementMapper, "dataElementMapper");
    }

    /**
     * subject の記録を全 provider から読み、未登録の data element を報告する。
     *
     * @param tenantId 照会者の tenant（必須）
     * @param subject  範囲確定で SELECTED にした本人
     */
    public SearchResult search(String tenantId, PrivacySubjectRef subject) {
        if (!StringUtils.hasText(tenantId) || subject == null) {
            throw BusinessException.of(400, PrivacyRequestService.ERROR_INVALID);
        }
        List<ProviderHit> hits = new ArrayList<>();
        int dropped = 0;
        for (ProductionPrivacyProvider provider : registry.providers()) {
            List<PrivacyRecord> found;
            try {
                found = provider.findBySubject(tenantId, subject);
            } catch (RuntimeException e) {
                log.warn("[privacy-dsar] 検索でproviderを照会できません: provider={} exceptionClass={}",
                        provider.providerId(), e.getClass().getSimpleName());
                throw BusinessException.of(503, ERROR_SOURCE_UNAVAILABLE);
            }
            List<PrivacyRecord> kept = new ArrayList<>();
            for (PrivacyRecord record : found == null ? List.<PrivacyRecord>of() : found) {
                if (record == null) {
                    continue;
                }
                Object rowTenant = record.fields().get(TENANT_COLUMN);
                if (rowTenant != null && !tenantId.equals(String.valueOf(rowTenant))) {
                    dropped++;
                    continue;
                }
                kept.add(record);
            }
            hits.add(new ProviderHit(provider.providerId(), List.copyOf(kept)));
        }
        if (dropped > 0) {
            log.warn("{}: providerが照会のtenantと異なる行を返したため捨てました: rows={}", LOG_CODE_TENANT_MISMATCH, dropped);
        }
        return new SearchResult(tenantId, subject.ref(), List.copyOf(hits), unregistered(tenantId));
    }

    /** 照会の tenant の catalog のうち、provider が扱わない data element（理由付き）。 */
    private List<UnregisteredElement> unregistered(String tenantId) {
        List<PiiDataElement> rows;
        try {
            rows = dataElementMapper.selectList(new LambdaQueryWrapper<PiiDataElement>()
                    .select(PiiDataElement::getId, PiiDataElement::getTenantId, PiiDataElement::getDataElementId,
                            PiiDataElement::getStorageKind, PiiDataElement::getStorageRef,
                            PiiDataElement::getProviderId, PiiDataElement::getSubjectAttribution,
                            PiiDataElement::getOwnerState, PiiDataElement::getInventoryStatus,
                            PiiDataElement::getInventoryRef)
                    .eq(PiiDataElement::getTenantId, tenantId)
                    .orderByAsc(PiiDataElement::getDataElementId));
        } catch (RuntimeException e) {
            log.warn("[privacy-dsar] 検索でdata element catalogを照会できません: exceptionClass={}",
                    e.getClass().getSimpleName());
            throw BusinessException.of(503, ERROR_SOURCE_UNAVAILABLE);
        }
        List<PiiDataElement> sameTenant = rows == null ? List.of()
                : rows.stream().filter(row -> row != null && tenantId.equals(row.getTenantId())).toList();
        List<UnregisteredElement> result = new ArrayList<>();
        for (PrivacyCoverageReport.ElementEntry entry
                : PrivacyCoverageReport.build(tenantId, sameTenant, registry).unregisteredElements()) {
            result.add(new UnregisteredElement(entry.providerId(), entry.dataElementId(), entry.reason()));
        }
        return List.copyOf(result);
    }

    /**
     * 検索の結果。
     *
     * @param tenantId     照会した tenant
     * @param subjectRef   本人の subject 参照
     * @param hits         provider ごとの行（provider ID の昇順。値を含む）
     * @param unregistered provider が扱わない data element（要手動確認）
     */
    public record SearchResult(String tenantId, String subjectRef, List<ProviderHit> hits,
                               List<UnregisteredElement> unregistered) {
        public SearchResult {
            hits = List.copyOf(hits);
            unregistered = List.copyOf(unregistered);
        }

        /** 全 provider の行数。 */
        public int recordCount() {
            return hits.stream().mapToInt(hit -> hit.records().size()).sum();
        }
    }

    /** 1つの provider の行。 */
    public record ProviderHit(String providerId, List<PrivacyRecord> records) {
        public ProviderHit {
            records = List.copyOf(records);
        }
    }

    /** provider が扱わない data element。 */
    public record UnregisteredElement(String providerId, String dataElementId, String reason) {
    }
}
