package com.ses.privacy.production.dsar;

import com.ses.privacy.production.provider.ProductionPrivacyProvider;
import com.ses.privacy.production.provider.ProviderDataElement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;

/**
 * 範囲 snapshot（production-bridge-phased-plan.md §3.5 の SCOPED: provider 一覧・data element・as-of の hash）。
 *
 * <p>正規形は1行目が版、以降は「key=value」を固定順で LF 終端する。provider は ID の昇順、data element の宣言は
 * {@code <provider>/<宣言>} の昇順（接頭辞の宣言は末尾に {@code *}、除外は {@code !<ID>} を昇順で連ねる）。as-of は JST の
 * {@code yyyy-MM-dd'T'HH:mm:ss.SSSSSS}（{@code t_privacy_action.created_at} の精度）。SCOPED の action 行が as-of・hash・
 * element 数を持つため、A2b は同じ正規形で再計算して、確定後に provider の登録が変わっていないことを照合できる。
 * hash は subject 参照を含むため監査本文には入れない（{@link PrivacyDsarAudit}）。
 */
final class PrivacyDsarScopeSnapshot {

    static final String FORMAT_VERSION = "nf07-dsar-scope-snapshot-v1";
    static final DateTimeFormatter AS_OF = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");

    private PrivacyDsarScopeSnapshot() {
    }

    /**
     * snapshot を作る。
     *
     * @param tenantId      tenant
     * @param requestId     request ID
     * @param subjectRef    SELECTED の subject 参照
     * @param d06DecisionId 適用した D-06 の決定ID
     * @param asOf          JST の確定時刻（マイクロ秒に切り詰めた値）
     * @param providers     登録済み provider
     */
    static Snapshot of(String tenantId, long requestId, String subjectRef, String d06DecisionId, LocalDateTime asOf,
                       List<ProductionPrivacyProvider> providers) {
        StringBuilder canonical = new StringBuilder();
        line(canonical, null, FORMAT_VERSION);
        line(canonical, "tenant-id", tenantId);
        line(canonical, "request-id", Long.toString(requestId));
        line(canonical, "subject", subjectRef);
        line(canonical, "d06.decision-id", d06DecisionId);
        line(canonical, "as-of", AS_OF.format(asOf));
        List<ProductionPrivacyProvider> sorted = new ArrayList<>(providers);
        sorted.sort(Comparator.comparing(ProductionPrivacyProvider::providerId));
        TreeSet<String> elements = new TreeSet<>();
        for (ProductionPrivacyProvider provider : sorted) {
            line(canonical, "provider", provider.providerId());
            for (ProviderDataElement element : provider.dataElements()) {
                elements.add(provider.providerId() + "/" + declaration(element));
            }
        }
        for (String element : elements) {
            line(canonical, "element", element);
        }
        return new Snapshot(sha256(canonical.toString()), elements.size(), sorted.size());
    }

    private static String declaration(ProviderDataElement element) {
        if (!element.prefixMatch()) {
            return element.dataElementId();
        }
        StringBuilder value = new StringBuilder(element.dataElementId()).append('*');
        new TreeSet<>(element.excludedIds()).forEach(excluded -> value.append('!').append(excluded));
        return value.toString();
    }

    private static void line(StringBuilder sb, String key, String value) {
        if (key != null) {
            sb.append(key).append('=');
        }
        sb.append(value == null ? "" : value).append('\n');
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256が利用できません", e);
        }
    }

    /** snapshot の hash と件数。 */
    record Snapshot(String hash, int elementCount, int providerCount) {
    }
}
