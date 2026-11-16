package com.ses.privacy.production;

import com.ses.entity.privacy.PiiDataElement;
import com.ses.privacy.production.provider.ProductionPrivacyProvider;
import com.ses.privacy.production.provider.ProviderDataElement;
import com.ses.privacy.production.provider.SubjectLink;
import com.ses.privacy.production.retention.catalog.SubjectAttribution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * NF07 data element catalog と本番 provider の対応表（production-bridge-phased-plan.md §3.2）。
 *
 * <p>catalog の各行を、provider が扱う（登録済み）か扱わない（未登録）かに分ける。未登録の行は検索・処分の対象外で、
 * 理由を必ず持つ。
 * <ul>
 *   <li>{@link #PROVIDER_UNREGISTERED}: {@code provider_id IS NULL}、または registry に無い provider ID</li>
 *   <li>{@link #PROVIDER_ELEMENT_UNDECLARED}: provider はあるが、その data element を宣言していない</li>
 *   <li>{@link #ATTRIBUTION_MISMATCH}: catalog の分類が provider の宣言と一致しない（UNKNOWN を含む）</li>
 * </ul>
 * provider が個別に宣言しているのに catalog に行が無い data element は {@link #CATALOG_MISSING} として別に報告する
 * （接頭辞の宣言は未知の種別の受け皿なので数えない）。catalog の分類は provider の宣言と一致しなければ登録済みにしないため、
 * 「帰属できない data element」（§3.4.3 規則5）の入力は catalog の {@code subject_attribution} だけでよい。
 * 本 report は状態を持たない計算で、DB にも provider にも触れない（provider の照会は呼ばない）。
 *
 * @param tenantId                    対象 tenant
 * @param providers                   登録済み provider（列 allow-list と本人の導出規則）
 * @param registeredElements          provider が扱う data element
 * @param unregisteredElements        provider が扱わない data element（理由付き）
 * @param uncatalogedProviderElements provider が宣言しているが catalog に無い data element
 * @param summary                     件数
 */
public record PrivacyCoverageReport(String tenantId,
                                    List<ProviderEntry> providers,
                                    List<ElementEntry> registeredElements,
                                    List<ElementEntry> unregisteredElements,
                                    List<MissingElement> uncatalogedProviderElements,
                                    Summary summary) {

    public static final String PROVIDER_UNREGISTERED = ProductionPrivacyProviderRegistry.PROVIDER_UNREGISTERED;
    public static final String PROVIDER_ELEMENT_UNDECLARED = "PROVIDER_ELEMENT_UNDECLARED";
    public static final String ATTRIBUTION_MISMATCH = "ATTRIBUTION_MISMATCH";
    public static final String CATALOG_MISSING = "CATALOG_MISSING";

    public PrivacyCoverageReport {
        providers = List.copyOf(providers);
        registeredElements = List.copyOf(registeredElements);
        unregisteredElements = List.copyOf(unregisteredElements);
        uncatalogedProviderElements = List.copyOf(uncatalogedProviderElements);
    }

    /** catalog の行と registry から report を作る。 */
    public static PrivacyCoverageReport build(String tenantId, List<PiiDataElement> catalog,
                                              ProductionPrivacyProviderRegistry registry) {
        List<PiiDataElement> rows = new ArrayList<>(catalog == null ? List.of() : catalog);
        rows.removeIf(row -> row == null || row.getDataElementId() == null);
        rows.sort(Comparator.comparing(PiiDataElement::getDataElementId));

        List<ElementEntry> registered = new ArrayList<>();
        List<ElementEntry> unregistered = new ArrayList<>();
        int unknownAttribution = 0;
        for (PiiDataElement row : rows) {
            SubjectAttribution attribution = SubjectAttribution.fromStored(row.getSubjectAttribution());
            if (!attribution.isClassified()) {
                unknownAttribution++;
            }
            String reason = reason(row, attribution, registry);
            ElementEntry entry = new ElementEntry(row.getDataElementId(), row.getStorageKind(), row.getStorageRef(),
                    row.getProviderId(), attribution.name(), attribution.isClassified(), row.getOwnerState(),
                    row.getInventoryStatus(), row.getInventoryRef(), reason);
            (reason == null ? registered : unregistered).add(entry);
        }

        List<ProviderEntry> providerEntries = new ArrayList<>();
        List<MissingElement> missing = new ArrayList<>();
        for (ProductionPrivacyProvider provider : registry.providers()) {
            List<ProviderElementEntry> declared = new ArrayList<>();
            for (ProviderDataElement element : provider.dataElements()) {
                declared.add(new ProviderElementEntry(element.dataElementId(), element.prefixMatch(),
                        List.copyOf(new java.util.TreeSet<>(element.excludedIds())), element.attribution().name(),
                        element.deliveryClass().name(),
                        element.subjectLinks().stream().map(ProviderSubjectRule::of).toList()));
                boolean cataloged = rows.stream().anyMatch(row -> element.matches(row.getDataElementId())
                        && provider.providerId().equals(row.getProviderId()));
                // 接頭辞の宣言は未知の種別の受け皿で、catalog に行が無くても欠落ではない（個別の宣言だけを数える）
                if (!cataloged && !element.prefixMatch()) {
                    missing.add(new MissingElement(provider.providerId(), element.dataElementId(), element.prefixMatch(),
                            CATALOG_MISSING));
                }
            }
            providerEntries.add(new ProviderEntry(provider.providerId(), declared, provider.columnAllowList()));
        }
        Summary summary = new Summary(registered.size(), unregistered.size(), unknownAttribution, missing.size());
        return new PrivacyCoverageReport(tenantId, providerEntries, registered, unregistered, missing, summary);
    }

    private static String reason(PiiDataElement row, SubjectAttribution attribution,
                                 ProductionPrivacyProviderRegistry registry) {
        Optional<ProductionPrivacyProvider> provider = registry.find(row.getProviderId());
        if (provider.isEmpty()) {
            return PROVIDER_UNREGISTERED;
        }
        Optional<ProviderDataElement> declaration =
                ProductionPrivacyProviderRegistry.declaration(provider.get(), row.getDataElementId());
        if (declaration.isEmpty()) {
            return PROVIDER_ELEMENT_UNDECLARED;
        }
        if (declaration.get().attribution() != attribution) {
            return ATTRIBUTION_MISMATCH;
        }
        return null;
    }

    /** 登録済み provider。 */
    public record ProviderEntry(String providerId, List<ProviderElementEntry> dataElements,
                                Map<String, List<String>> columnAllowList) {
        public ProviderEntry {
            dataElements = List.copyOf(dataElements);
            // 表の順序を保つ（Map.copyOf は順序を保証しない）
            columnAllowList = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(columnAllowList));
        }
    }

    /** provider が宣言する data element（接頭辞の除外・本人帰属・DSAR 交付区分・本人の導出規則）。 */
    public record ProviderElementEntry(String dataElementId, boolean prefixMatch, List<String> excludedIds,
                                       String subjectAttribution, String deliveryClass,
                                       List<ProviderSubjectRule> subjectRules) {
        public ProviderElementEntry {
            excludedIds = List.copyOf(excludedIds);
            subjectRules = List.copyOf(subjectRules);
        }
    }

    /** 本人の導出規則の1項（表示用）。 */
    public record ProviderSubjectRule(String subjectType, String source) {
        static ProviderSubjectRule of(SubjectLink link) {
            return new ProviderSubjectRule(link.type().name(), link.source());
        }
    }

    /** catalog の1行。{@code reason} が null なら登録済み。 */
    public record ElementEntry(String dataElementId, String storageKind, String storageRef, String providerId,
                               String subjectAttribution, boolean attributionClassified, String ownerState,
                               String inventoryStatus, String inventoryRef, String reason) {
    }

    /** provider が宣言しているが catalog に無い data element。 */
    public record MissingElement(String providerId, String dataElementId, boolean prefixMatch, String reason) {
    }

    /** 件数。 */
    public record Summary(int registeredCount, int unregisteredCount, int unknownAttributionCount,
                          int uncatalogedProviderElementCount) {
    }
}
