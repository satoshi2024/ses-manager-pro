package com.ses.privacy.production.dsar;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.privacy.production.ProductionPrivacyProviderRegistry;
import com.ses.privacy.production.provider.DsarDeliveryClass;
import com.ses.privacy.production.provider.PrivacyRecord;
import com.ses.privacy.production.redaction.ThirdPartyRedactor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * DSAR の草案 package の内容（NF07-A2b、production-bridge-phased-plan.md §3.5.2・§5.2「DSAR 草案 package」）。
 *
 * <p>検索の各行を data element ごとに {@link ThirdPartyRedactor} へ渡し、自動で入れてよい field の値だけを package に書く。
 * <ul>
 *   <li>行が属する data element が全て交付区分 {@link DsarDeliveryClass#SELF} なら、行の field は本人の field として渡す（規則が
 *       自動交付を許す field だけが自動で入る）。</li>
 *   <li>行が1つでも {@link DsarDeliveryClass#MIXED_THIRD_PARTY}（provider の宣言が無い data element を含む）の data element に属すれば、
 *       行の field は帰属不明として渡し、規則があっても1件も自動で入れない（redactor が {@link ThirdPartyRedactor#MIXED_MANUAL_ONLY}
 *       で固定する。F2 review）。</li>
 *   <li>規則が未設定（第1期の既定）なら全 field が要手動確認で、自動で入る値は0件（{@link ThirdPartyRedactor#RULES_UNSET}）。</li>
 * </ul>
 * 要手動確認・第三者として保留した項目は値を書かず、provider・data element・理由コード・件数だけを書く。provider が扱わない
 * data element（catalog の未登録）も要手動確認に並べる。JSON の key 順と行の順（provider・表・行 ID・data element）は固定し、
 * 内容 hash（SHA-256）を承認・交付記録の照合に使う。
 */
final class PrivacyExportPackageContent {

    static final String FORMAT_VERSION = "nf07-dsar-export-package-v1";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final ObjectMapper JSON = new ObjectMapper();

    private PrivacyExportPackageContent() {
    }

    /**
     * package を作る。
     *
     * @param header   case の情報
     * @param search   検索の結果
     * @param redactor 第三者情報の判定
     * @param registry data element の交付区分の取得元
     */
    static Built build(Header header, PrivacySearchService.SearchResult search, ThirdPartyRedactor redactor,
                       ProductionPrivacyProviderRegistry registry) {
        Map<String, ManualKey> manual = new TreeMap<>();
        Map<String, int[]> withheld = new TreeMap<>();
        Map<String, Map<String, Object>> released = new LinkedHashMap<>();
        List<Map<String, Object>> providers = new ArrayList<>();
        int autoReleasedFields = 0;
        for (PrivacySearchService.ProviderHit hit : search.hits()) {
            Map<String, Object> providerEntry = new LinkedHashMap<>();
            providerEntry.put("providerId", hit.providerId());
            providerEntry.put("recordCount", hit.records().size());
            providers.add(providerEntry);
            List<PrivacyRecord> records = new ArrayList<>(hit.records());
            records.sort(Comparator.comparing(PrivacyRecord::table).thenComparingLong(PrivacyRecord::rowId));
            for (PrivacyRecord record : records) {
                // 行の field は行全体の列で、data element ごとに分かれていない。行が1つでも第三者混在（または宣言の無い）
                // data element に属するなら、その行は全ての data element で第三者混在として扱う（SELF の element 経由で漏らさない）
                DsarDeliveryClass deliveryClass = recordDeliveryClass(record, registry);
                for (String elementId : new java.util.TreeSet<>(record.dataElementIds())) {
                    ThirdPartyRedactor.Outcome outcome = redactor.redact(deliveryClass, fields(record, deliveryClass));
                    if (!outcome.manualReview().isEmpty()) {
                        manual.computeIfAbsent(hit.providerId() + "\n" + elementId + "\n" + outcome.reason(),
                                key -> new ManualKey(hit.providerId(), elementId, outcome.reason())).records++;
                    }
                    if (!outcome.withheldFieldIds().isEmpty()) {
                        withheld.computeIfAbsent(hit.providerId() + "\n" + elementId, key -> new int[1])[0]
                                += outcome.withheldFieldIds().size();
                    }
                    for (ThirdPartyRedactor.Field field : outcome.autoReleased()) {
                        String rowKey = hit.providerId() + "\n" + record.table() + "\n" + record.rowId() + "\n" + elementId;
                        Map<String, Object> row = released.computeIfAbsent(rowKey, key -> releasedRow(hit.providerId(),
                                elementId, record));
                        @SuppressWarnings("unchecked")
                        Map<String, Object> values = (Map<String, Object>) row.get("fields");
                        if (!values.containsKey(field.fieldId())) {
                            values.put(field.fieldId(), jsonValue(field.value()));
                            autoReleasedFields++;
                        }
                    }
                }
            }
        }
        List<ManualItem> manualItems = new ArrayList<>();
        manual.values().forEach(key -> manualItems.add(new ManualItem(key.providerId, key.dataElementId, key.reason,
                key.records)));
        for (PrivacySearchService.UnregisteredElement element : search.unregistered()) {
            manualItems.add(new ManualItem(element.providerId(), element.dataElementId(), element.reason(), null));
        }
        String redactionReason = redactor.redact(DsarDeliveryClass.SELF, List.of()).reason();

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", FORMAT_VERSION);
        document.put("requestNo", header.requestNo());
        document.put("requestType", header.requestType());
        document.put("subject", header.subjectRef());
        document.put("identityRuleRef", header.identityRuleRef());
        document.put("scopeAsOf", header.scopeAsOf() == null ? null : TIME.format(header.scopeAsOf()));
        document.put("generatedAt", TIME.format(header.generatedAt()));
        document.put("redaction", redactionReason);
        document.put("providers", providers);
        document.put("autoReleased", new ArrayList<>(released.values()));
        List<Map<String, Object>> manualJson = new ArrayList<>();
        for (ManualItem item : manualItems) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("providerId", item.providerId());
            entry.put("dataElementId", item.dataElementId());
            entry.put("reason", item.reason());
            entry.put("recordCount", item.recordCount());
            manualJson.add(entry);
        }
        document.put("manualReview", manualJson);
        List<Map<String, Object>> withheldJson = new ArrayList<>();
        withheld.forEach((key, count) -> {
            String[] parts = key.split("\n", 2);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("providerId", parts[0]);
            entry.put("dataElementId", parts[1]);
            entry.put("fieldCount", count[0]);
            withheldJson.add(entry);
        });
        document.put("withheldThirdParty", withheldJson);
        byte[] bytes;
        try {
            bytes = JSON.writeValueAsBytes(document);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("開示packageを作れません", e);
        }
        return new Built(bytes, sha256(bytes), autoReleasedFields, redactionReason, List.copyOf(manualItems));
    }

    /** 行の交付区分。行の data element が全て {@link DsarDeliveryClass#SELF} のときだけ SELF（空なら第三者混在）。 */
    static DsarDeliveryClass recordDeliveryClass(PrivacyRecord record, ProductionPrivacyProviderRegistry registry) {
        if (record.dataElementIds().isEmpty()) {
            return DsarDeliveryClass.MIXED_THIRD_PARTY;
        }
        for (String elementId : record.dataElementIds()) {
            if (registry.deliveryClassOf(elementId) != DsarDeliveryClass.SELF) {
                return DsarDeliveryClass.MIXED_THIRD_PARTY;
            }
        }
        return DsarDeliveryClass.SELF;
    }

    /** 行の field（表.列）。SELF の行は本人の field、それ以外は帰属不明として渡す。 */
    private static List<ThirdPartyRedactor.Field> fields(PrivacyRecord record, DsarDeliveryClass deliveryClass) {
        ThirdPartyRedactor.Ownership ownership = deliveryClass == DsarDeliveryClass.SELF
                ? ThirdPartyRedactor.Ownership.SUBJECT : ThirdPartyRedactor.Ownership.UNKNOWN;
        List<ThirdPartyRedactor.Field> fields = new ArrayList<>();
        Set<String> columns = new LinkedHashSet<>(record.fields().keySet());
        for (String column : columns) {
            fields.add(new ThirdPartyRedactor.Field(record.table() + "." + column, record.fields().get(column), ownership));
        }
        return fields;
    }

    private static Map<String, Object> releasedRow(String providerId, String elementId, PrivacyRecord record) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("providerId", providerId);
        row.put("dataElementId", elementId);
        row.put("table", record.table());
        row.put("rowId", record.rowId());
        row.put("logicallyDeleted", record.logicallyDeleted());
        row.put("fields", new TreeMap<String, Object>());
        return row;
    }

    /** JSON に書く値（文字列・数値・真偽値・null。byte 列は Base64、それ以外は文字列表現）。 */
    private static Object jsonValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof LocalDateTime time) {
            return TIME.format(time);
        }
        return String.valueOf(value);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256が利用できません", e);
        }
    }

    static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * case の情報（package の見出し）。
     *
     * @param requestNo       受付番号
     * @param requestType     請求の種別
     * @param subjectRef      本人の subject 参照（package は本人へ交付するため含める。監査・log には入れない）
     * @param identityRuleRef 本人確認で適用した D-06 の決定ID
     * @param scopeAsOf       範囲確定の as-of
     * @param generatedAt     作成日時（JST）
     */
    record Header(String requestNo, String requestType, String subjectRef, String identityRuleRef,
                  LocalDateTime scopeAsOf, LocalDateTime generatedAt) {
    }

    /**
     * 作った package。
     *
     * @param bytes              JSON の内容
     * @param sha256             内容 hash
     * @param autoReleasedFields 自動で入れた field の数
     * @param redactionReason    規則の適用状態（{@link ThirdPartyRedactor#RULES_UNSET} / {@link ThirdPartyRedactor#RULES_APPLIED}）
     * @param manualReview       要手動確認の一覧
     */
    record Built(byte[] bytes, String sha256, int autoReleasedFields, String redactionReason,
                 List<ManualItem> manualReview) {
    }

    /** 要手動確認の1項目（値を持たない）。 */
    record ManualItem(String providerId, String dataElementId, String reason, Integer recordCount) {
    }

    private static final class ManualKey {
        private final String providerId;
        private final String dataElementId;
        private final String reason;
        private int records;

        private ManualKey(String providerId, String dataElementId, String reason) {
            this.providerId = providerId;
            this.dataElementId = dataElementId;
            this.reason = reason;
        }
    }
}
